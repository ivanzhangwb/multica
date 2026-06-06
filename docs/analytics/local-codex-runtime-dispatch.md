# Local Codex Runtime Dispatch

This document explains how a local `codex` runtime is discovered, registered,
scheduled, executed on a user's machine, and reported back to the Multica
server. It focuses on the daemon path rather than the user-facing runtime
management CLI.

## Scope

`server/cmd/multica/cmd_runtime.go` only implements management commands:

- `multica runtime list`
- `multica runtime usage <runtime-id>`
- `multica runtime activity <runtime-id>`
- `multica runtime update <runtime-id>`

The code that actually starts processes on the user's computer lives in the
daemon path:

- `server/cmd/multica/cmd_daemon.go`
- `server/internal/daemon/`
- `server/pkg/agent/codex.go`
- `server/internal/handler/daemon.go`

## High-Level Flow

```text
user runs multica daemon start
        |
        v
multica spawns or runs a foreground daemon
        |
        v
daemon detects local agent CLIs, including codex
        |
        v
daemon registers a local codex runtime with the server
        |
        v
daemon keeps heartbeat / websocket wakeup / poll loops alive
        |
        v
daemon claims a queued task for that runtime
        |
        v
daemon prepares a per-task environment on the user's machine
        |
        v
daemon starts: codex app-server --listen stdio://
        |
        v
daemon streams messages, token usage, session pointers, and terminal result
back to the server
```

The server never directly starts `codex` on the user's machine. The server
queues work and exposes daemon APIs. The local daemon, already running on the
user's machine, claims work and starts the local process with `os/exec`.

## Starting the Local Daemon

`multica daemon start` is defined in `server/cmd/multica/cmd_daemon.go`.

In background mode, `runDaemonBackground` resolves the current `multica`
executable and starts a detached child process with the forwarded daemon args:

```text
multica daemon start --foreground ...
```

Stdout and stderr are written to the profile's daemon log. The parent process
does not wait for the child; it releases the process and writes a daemon PID
file.

In foreground mode, the daemon runs in the current terminal.

## Local Codex Detection

Daemon config is built in `server/internal/daemon/config.go`.

Codex is registered as an available agent when one of these probes succeeds:

- `MULTICA_CODEX_PATH`
- `codex` on the daemon process `PATH`
- `codex` resolved by the user's login shell
- Codex Desktop's bundled CLI path on macOS

If found, the daemon stores an `AgentEntry` for provider `codex` with:

- executable path
- optional model override from `MULTICA_CODEX_MODEL`

The daemon also detects the Codex CLI version before registering the runtime.
Too-old versions are skipped.

## Runtime Registration

The daemon registers one runtime per detected provider for every workspace the
authenticated user belongs to.

Client call:

```text
POST /api/daemon/register
```

Request data includes:

| Field | Meaning |
|---|---|
| `workspace_id` | Workspace being watched by this daemon. |
| `daemon_id` | Stable local daemon identity. |
| `legacy_daemon_ids` | Historical daemon IDs for runtime row migration. |
| `device_name` | Human-readable device name. |
| `cli_version` | Version of the `multica` CLI. |
| `launched_by` | `"desktop"` when spawned by the Electron app; empty for standalone CLI. |
| `runtimes` | Local provider registrations, including `name`, `type`, `version`, and `status`. |

For Codex, a runtime entry looks conceptually like:

```json
{
  "name": "Codex (Ivan's MacBook)",
  "type": "codex",
  "version": "codex-cli-version",
  "status": "online"
}
```

Server handler:

```text
server/internal/handler/daemon.go: DaemonRegister
```

The server upserts `agent_runtime` with:

- `runtime_mode = "local"`
- `provider = "codex"`
- `status = "online"` or `"offline"`
- `device_info`
- metadata containing runtime version, `cli_version`, and `launched_by`
- `owner_id` when registration is authenticated by a user token

The server response gives the daemon the runtime IDs, workspace repos, repo
version, and workspace settings.

## Heartbeat and Wakeup

The daemon keeps runtime freshness and control-plane actions alive through:

- HTTP heartbeat: `POST /api/daemon/heartbeat`
- daemon websocket: `GET /api/daemon/ws`
- fallback polling per runtime

Heartbeat request data includes:

| Field | Meaning |
|---|---|
| `runtime_id` | Runtime row being heartbeated. |
| `supports_batch_import` | Whether the daemon supports batch local-skill import. |

The heartbeat response can carry pending daemon-side actions such as:

- CLI update requests
- model list requests
- local skill list requests
- local skill import requests

Task wakeups are best-effort. Even if websocket wakeup is unavailable, the
per-runtime poll loop keeps claiming on its configured interval.

## Claim and Dispatch

Each registered runtime has its own poller in `server/internal/daemon/daemon.go`.

The poller first acquires a local concurrency slot, then claims a task:

```text
POST /api/daemon/runtimes/{runtimeId}/tasks/claim
```

This order is intentional. It prevents tasks from being claimed into the
server-side `dispatched` state while the local daemon has no capacity to start
them.

Server handler:

```text
server/internal/handler/daemon.go: ClaimTaskByRuntime
```

Claim response includes task and execution context:

| Data | Examples |
|---|---|
| Task identity | `task_id`, `runtime_id`, `workspace_id`, `issue_id`, `chat_session_id`, `autopilot_run_id`. |
| Agent config | name, instructions, skills, custom env, custom args, MCP config, model, thinking level. |
| Workspace context | workspace prompt/context, repos, project resources. |
| Trigger context | triggering comment body, author type/name, new comment count, chat message, attachments, autopilot payload. |
| Resume context | prior session ID and prior workdir. |
| Requesting user context | runtime owner's name and profile description. |
| Auth | task-scoped `auth_token` for the spawned agent process. |

After claim, the daemon calls:

```text
POST /api/daemon/tasks/{taskId}/start
POST /api/daemon/tasks/{taskId}/progress
```

The first call moves the task into running state. The progress call broadcasts
a user-visible launch status such as `Launching codex`.

## Local Environment Preparation

Before starting Codex, the daemon prepares a per-task execution environment.

Key setup includes:

- per-task workdir under the daemon workspaces root, or a locked
  `local_directory` project resource path
- per-task runtime files and sidecars such as `.agent_context`
- provider-specific files such as Codex `AGENTS.md` / skills / `CODEX_HOME`
- optional reuse of a previous workdir for the same agent and issue
- workspace/project/repo metadata for `multica repo checkout`
- agent skills and runtime workflow instructions
- cleanup hooks for local-directory tasks so user repos are restored after run

The daemon injects environment variables into the child agent process:

| Variable | Meaning |
|---|---|
| `MULTICA_TOKEN` | Prefer task-scoped token minted at claim time; fallback to daemon token for legacy cases. |
| `MULTICA_SERVER_URL` | Server API base URL. |
| `MULTICA_DAEMON_PORT` | Local daemon health/helper port. |
| `MULTICA_WORKSPACE_ID` | Current workspace. |
| `MULTICA_AGENT_NAME` | Agent display name. |
| `MULTICA_AGENT_ID` | Agent ID. |
| `MULTICA_TASK_ID` | Current task ID. |
| `MULTICA_TASK_SLOT` | Local concurrency slot index. |
| `MULTICA_AUTOPILOT_RUN_ID` | Present for autopilot tasks. |
| `MULTICA_AUTOPILOT_ID` | Present for autopilot tasks. |
| `MULTICA_QUICK_CREATE_TASK_ID` | Present for quick-create tasks. |
| `CODEX_HOME` | Per-task Codex home, when prepared. |
| `PATH` | Prepended with the current `multica` binary directory. |

Agent custom env is also injected, except for daemon-internal protected keys.

## Starting Codex

Codex execution is implemented in `server/pkg/agent/codex.go`.

The backend starts a local process:

```text
codex app-server --listen stdio://
```

The Go call is:

```go
exec.CommandContext(runCtx, execPath, codexArgs...)
```

The daemon communicates with Codex over JSON-RPC through the process stdin and
stdout. The lifecycle is:

1. `initialize`
2. `initialized`
3. `thread/start` or `thread/resume`
4. `turn/start`
5. wait for `turn/completed`, final answer, cancellation, timeout, or error

Codex MCP config is written to the per-task `$CODEX_HOME/config.toml` when the
agent has managed MCP config. This keeps MCP secrets out of argv, process
listings, and daemon command logs.

## Runtime Message Reporting

During execution, the daemon drains the agent message stream and reports
batches to:

```text
POST /api/daemon/tasks/{taskId}/messages
```

Reported message fields:

| Field | Meaning |
|---|---|
| `seq` | Per-task monotonically increasing sequence. |
| `type` | `thinking`, `text`, `tool_use`, `tool_result`, or `error`. |
| `tool` | Tool name for tool events. |
| `content` | Text/thinking/error content. |
| `input` | Tool input JSON. |
| `output` | Tool output text, truncated by daemon before send when very large. |

Codex events are normalized as:

| Codex signal | Multica message |
|---|---|
| agent message / final answer | `text` |
| command execution started | `tool_use` with `tool = "exec_command"` |
| command execution completed | `tool_result` with `tool = "exec_command"` |
| file change started | `tool_use` with `tool = "patch_apply"` |
| file change completed | `tool_result` with `tool = "patch_apply"` |
| protocol error | `error` |

Server handler:

```text
server/internal/handler/daemon.go: ReportTaskMessages
```

The server redacts sensitive content, writes rows to `task_message`, and
publishes realtime `task:message` events to connected clients.

## Session Pinning

When Codex reveals its thread ID, the daemon pins the resume pointer:

```text
POST /api/daemon/tasks/{taskId}/session
```

Sent data:

| Field | Meaning |
|---|---|
| `session_id` | Codex thread ID. |
| `work_dir` | Task workdir. |

Server handler:

```text
server/internal/handler/task_lifecycle.go: PinTaskSession
```

This protects resume continuity if the daemon crashes before the final
complete/fail callback.

## Token Usage Reporting

After execution, the daemon reports token usage independently of terminal
status:

```text
POST /api/daemon/tasks/{taskId}/usage
```

Payload:

| Field | Meaning |
|---|---|
| `provider` | `codex`. |
| `model` | Model name, or `unknown` when Codex does not report one. |
| `input_tokens` | Input token count. |
| `output_tokens` | Output token count. |
| `cache_read_tokens` | Cache read token count. |
| `cache_write_tokens` | Cache write token count. |

Codex usage is collected from:

1. JSON-RPC notifications containing `usage`, `token_usage`, or `tokens`.
2. Fallback scan of Codex session JSONL files under `$CODEX_HOME/sessions` or
   `~/.codex/sessions`.

Server handler:

```text
server/internal/handler/daemon.go: ReportTaskUsage
```

The server upserts rows into `task_usage` keyed by `(task_id, provider, model)`.
The table feeds runtime, issue, and dashboard usage queries.

### How Codex Token Usage Is Collected

Codex token usage is not estimated by Multica. The daemon reads it from Codex
runtime outputs.

Primary path: Codex JSON-RPC notifications.

During the `codex app-server --listen stdio://` session, `codexClient` watches
turn/event payloads for any of these usage-bearing fields:

- `usage`
- `token_usage`
- `tokens`

It accepts several key conventions because Codex payloads have changed across
versions:

| Multica field | Codex keys accepted |
|---|---|
| `input_tokens` | `input_tokens`, `input`, `prompt_tokens` |
| `output_tokens` | `output_tokens`, `output`, `completion_tokens` |
| `cache_read_tokens` | `cache_read_tokens`, `cache_read_input_tokens` |
| `cache_write_tokens` | `cache_write_tokens`, `cache_creation_input_tokens` |

Those values accumulate in the in-memory Codex client for the current task.

Fallback path: Codex session JSONL files.

If JSON-RPC notifications produce no input or output tokens, the daemon scans
Codex session logs written after the task started:

```text
$CODEX_HOME/sessions/YYYY/MM/DD/*.jsonl
~/.codex/sessions/YYYY/MM/DD/*.jsonl
```

It looks for:

- `turn_context` events to recover the model name
- `token_count` events to recover token usage

For `token_count`, the scanner prefers `total_token_usage`, then falls back to
`last_token_usage`. It maps:

- `input_tokens`
- `output_tokens + reasoning_output_tokens`
- `cached_input_tokens` or `cache_read_input_tokens`

If a model is still unknown, the daemon reports model as `unknown`.

## Runtime and Agent Configuration Sources

There are two separate configuration directions:

1. Local daemon and runtime capability discovery flows from the user's machine
   to the server.
2. Agent/task execution config flows from the server to the daemon when a task
   is claimed.

### Local Runtime Discovery

At daemon startup, `LoadConfig` reads CLI flags, environment variables, and
local executable discovery results.

Important local runtime config includes:

| Config | Source |
|---|---|
| server URL | `MULTICA_SERVER_URL` or CLI override |
| daemon ID | local profile state / `MULTICA_DAEMON_ID` / CLI override |
| device name | `MULTICA_DAEMON_DEVICE_NAME` / CLI override |
| runtime display name | `MULTICA_AGENT_RUNTIME_NAME` / CLI override |
| workspaces root | `MULTICA_WORKSPACES_ROOT` / CLI override |
| heartbeat interval | `MULTICA_DAEMON_HEARTBEAT_INTERVAL` / CLI override |
| poll interval | `MULTICA_DAEMON_POLL_INTERVAL` / CLI override |
| max concurrency | `MULTICA_DAEMON_MAX_CONCURRENT_TASKS` / CLI override |
| task timeout | `MULTICA_AGENT_TIMEOUT` / CLI override |
| Codex semantic inactivity timeout | `MULTICA_CODEX_SEMANTIC_INACTIVITY_TIMEOUT` / CLI override |
| Codex executable | `MULTICA_CODEX_PATH`, PATH, login shell PATH, or Codex Desktop bundle |
| Codex default model | `MULTICA_CODEX_MODEL` |
| Codex default args | daemon-level Codex args |

Runtime registration sends only a summary of this local capability state to the
server: provider, runtime name, runtime version, device info, CLI version, and
launch source. Full local environment variables are not uploaded as runtime
registration metadata.

### Server-Initiated Runtime Metadata Requests

Some runtime information is requested lazily by the server and returned through
heartbeat-driven pending actions.

Model list flow:

1. A user/API calls `POST /api/runtimes/{runtimeId}/models`.
2. The server enqueues a pending model-list request.
3. A daemon heartbeat response includes `pending_model_list`.
4. The daemon calls `agent.ListModels(ctx, provider, executablePath)`.
5. The daemon reports back to:

```text
POST /api/daemon/runtimes/{runtimeId}/models/{requestId}/result
```

For Codex, `ListModels` starts from a static Codex model catalog and augments
it with thinking-level support discovered from the local Codex CLI when
possible. Discovery failure does not fail task execution; it just means the UI
may not show per-model thinking-level options.

Local skills flow:

1. A user/API calls `POST /api/runtimes/{runtimeId}/local-skills`.
2. The server enqueues a pending local-skill list request.
3. Heartbeat returns `pending_local_skills`.
4. The daemon lists provider-specific local skills.
5. The daemon reports back to:

```text
POST /api/daemon/runtimes/{runtimeId}/local-skills/{requestId}/result
```

Local skill import follows the same heartbeat request/result pattern through:

```text
POST /api/daemon/runtimes/{runtimeId}/local-skills/import/{requestId}/result
```

### Task Execution Config From Server

On claim, the server returns the task-specific config the daemon needs to run
Codex. This includes:

- agent instructions
- skills and skill files
- agent custom env
- agent custom args
- agent MCP config
- selected model
- selected thinking level
- workspace context
- project resources
- repo list
- trigger comment/chat/autopilot context
- prior session ID and prior workdir
- task-scoped auth token

The daemon then combines:

- local runtime config from `LoadConfig`
- runtime capability data from registration/model discovery
- task config returned by `ClaimTaskByRuntime`
- generated execution-context files from `execenv`

The result is passed into Codex through:

- process argv: `codex app-server --listen stdio://` plus filtered daemon and
  agent custom args
- process environment: `MULTICA_*`, `CODEX_HOME`, PATH, and allowed custom env
- workdir files: `AGENTS.md`, skills, `.agent_context`, sidecar manifests
- JSON-RPC calls: thread start/resume, model and thinking-level config, turn
  input prompt

## Terminal Result Reporting

Successful completion:

```text
POST /api/daemon/tasks/{taskId}/complete
```

Payload:

| Field | Meaning |
|---|---|
| `output` | Final agent output. |
| `session_id` | Codex thread ID. |
| `work_dir` | Workdir used for the task. |

Server handler:

```text
server/internal/handler/daemon.go: CompleteTask
```

Failure, timeout, blocked, or resume-unsafe terminal states:

```text
POST /api/daemon/tasks/{taskId}/fail
```

Payload:

| Field | Meaning |
|---|---|
| `error` | User-visible failure text. |
| `session_id` | Codex thread ID, when available. |
| `work_dir` | Workdir used for the task. |
| `failure_reason` | Stable classifier such as `timeout`, `idle_watchdog`, or provider-specific resume-unsafe reasons. |

Server handler:

```text
server/internal/handler/daemon.go: FailTask
```

Both completion and failure paths revoke the task-scoped token after the
terminal callback succeeds.

## Cancellation and Watchdogs

The daemon polls task status while Codex runs:

```text
GET /api/daemon/tasks/{taskId}/status
```

If the server reports cancellation or the task disappears, the daemon cancels
the Codex process context and discards the local result.

There are also local safety controls:

- overall agent timeout
- Codex semantic inactivity timeout
- first-turn no-progress timeout
- idle watchdog for a silent backend with no in-flight tool call

When a watchdog turns into a terminal blocked/fail state, token usage is still
reported before the terminal callback.

## Server-Side Storage Summary

| Data | Endpoint | Main storage/effect |
|---|---|---|
| Runtime registration | `/api/daemon/register` | Upserts `agent_runtime`; emits runtime analytics. |
| Heartbeat | `/api/daemon/heartbeat` | Updates runtime freshness and returns pending daemon actions. |
| Claim | `/api/daemon/runtimes/{runtimeId}/tasks/claim` | Moves queued task to dispatched and returns execution context. |
| Start | `/api/daemon/tasks/{taskId}/start` | Moves task to running. |
| Progress | `/api/daemon/tasks/{taskId}/progress` | Broadcasts progress via task service/realtime. |
| Messages | `/api/daemon/tasks/{taskId}/messages` | Redacts and inserts `task_message`; publishes realtime events. |
| Session | `/api/daemon/tasks/{taskId}/session` | Updates task `session_id` and `work_dir`. |
| Usage | `/api/daemon/tasks/{taskId}/usage` | Upserts `task_usage`. |
| Complete | `/api/daemon/tasks/{taskId}/complete` | Marks task complete, stores result, revokes task token. |
| Fail | `/api/daemon/tasks/{taskId}/fail` | Marks task failed/blocked, stores error metadata, revokes task token. |

## Privacy and Trust Boundaries

- The spawned Codex process receives a task-scoped token when the server can
  mint one. This limits what the agent can do compared with the daemon owner's
  full credential.
- Agent custom environment variables are injected locally, but daemon-protected
  keys cannot be overridden by agent config.
- Managed Codex MCP config is written to `$CODEX_HOME/config.toml` instead of
  argv to avoid leaking secrets through process listings and logs.
- Task messages are redacted server-side before persistence and realtime
  broadcast.
- Tool output is truncated before message reporting when it is very large.
- The server does not have direct process-control access to the user's machine;
  all process execution is initiated by the local daemon after authenticated
  claim.
