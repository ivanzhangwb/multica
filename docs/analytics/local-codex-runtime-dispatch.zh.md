# 本地 Codex 运行时调度链路

本文说明本地 `codex` 运行时如何被发现、注册、调度，如何在用户电脑上启动进程，以及执行过程中的消息、token 用量、会话指针和最终结果如何回传到 Multica 服务器。

本文关注守护进程（daemon）执行链路，而不是面向用户的 runtime 管理 CLI。

## 范围

`server/cmd/multica/cmd_runtime.go` 只实现 runtime 管理命令：

- `multica runtime list`
- `multica runtime usage <runtime-id>`
- `multica runtime activity <runtime-id>`
- `multica runtime update <runtime-id>`

真正负责在用户电脑上启动进程的代码位于：

- `server/cmd/multica/cmd_daemon.go`
- `server/internal/daemon/`
- `server/pkg/agent/codex.go`
- `server/internal/handler/daemon.go`

## 总体流程

```text
用户运行 multica daemon start
        |
        v
multica 启动前台或后台守护进程
        |
        v
守护进程探测本机 agent CLI，包括 codex
        |
        v
守护进程向服务器注册本地 codex 运行时
        |
        v
守护进程保持 heartbeat / websocket wakeup / poll loop
        |
        v
守护进程为该运行时 claim 一个排队中的 task
        |
        v
守护进程在用户电脑上准备 task 专属执行环境
        |
        v
守护进程启动：codex app-server --listen stdio://
        |
        v
守护进程把消息、token 用量、会话指针和最终结果回传给服务器
```

服务器不会直接远程启动用户电脑上的 `codex`。服务器只负责排队 task，并提供 daemon API。本地守护进程已经运行在用户电脑上，它主动 claim task，然后用 `os/exec` 启动本地 Codex 进程。

## 启动本地守护进程

`multica daemon start` 定义在 `server/cmd/multica/cmd_daemon.go`。

后台模式下，`runDaemonBackground` 会解析当前 `multica` 可执行文件路径，并启动一个分离的子进程：

```text
multica daemon start --foreground ...
```

子进程的 stdout 和 stderr 写入当前 profile 的 daemon log。父进程不会等待子进程结束，而是释放进程句柄，并写入 daemon PID 文件。

前台模式下，守护进程直接运行在当前终端里。

## 本地 Codex 探测

守护进程配置由 `server/internal/daemon/config.go` 构建。

满足任一探测路径时，Codex 会被注册为可用 agent：

- `MULTICA_CODEX_PATH`
- 守护进程 `PATH` 里的 `codex`
- 用户 login shell 解析出的 `codex`
- macOS 上 Codex Desktop 内置的 CLI 路径

探测成功后，守护进程会为 provider `codex` 保存一个 `AgentEntry`：

- 可执行文件路径
- 来自 `MULTICA_CODEX_MODEL` 的可选默认模型

注册运行时之前，守护进程还会检测 Codex CLI 版本。版本过旧的 runtime 会被跳过。

## 注册运行时

守护进程会为认证用户所属的每个工作区注册本机已发现的运行时。每个 provider 对应一个 runtime。

客户端调用：

```text
POST /api/daemon/register
```

请求数据包括：

| 字段 | 含义 |
|---|---|
| `workspace_id` | 当前守护进程正在监听的工作区。 |
| `daemon_id` | 稳定的本地守护进程身份。 |
| `legacy_daemon_ids` | 历史 daemon ID，用于迁移旧 runtime row。 |
| `device_name` | 用户设备的可读名称。 |
| `cli_version` | `multica` CLI 版本。 |
| `launched_by` | Electron app 启动时为 `"desktop"`；独立 CLI 启动时为空。 |
| `runtimes` | 本地 provider 注册信息，包括 `name`、`type`、`version`、`status`。 |

Codex runtime 的概念结构类似：

```json
{
  "name": "Codex (Ivan's MacBook)",
  "type": "codex",
  "version": "codex-cli-version",
  "status": "online"
}
```

服务端处理函数：

```text
server/internal/handler/daemon.go: DaemonRegister
```

服务器会 upsert `agent_runtime`：

- `runtime_mode = "local"`
- `provider = "codex"`
- `status = "online"` 或 `"offline"`
- `device_info`
- metadata 中包含 runtime version、`cli_version` 和 `launched_by`
- 使用用户 token 注册时写入 `owner_id`

服务器响应会把 runtime ID、工作区 repo、repo version 和工作区设置返回给守护进程。

## Heartbeat 与唤醒

守护进程通过以下机制维持运行时新鲜度，并接收控制面动作：

- HTTP heartbeat：`POST /api/daemon/heartbeat`
- daemon websocket：`GET /api/daemon/ws`
- 每个 runtime 的 fallback poll loop

Heartbeat 请求数据包括：

| 字段 | 含义 |
|---|---|
| `runtime_id` | 正在 heartbeat 的 runtime row。 |
| `supports_batch_import` | 守护进程是否支持批量导入本地 skill。 |

Heartbeat 响应可能携带待处理动作，例如：

- CLI update request
- model list request
- local skill list request
- local skill import request

Task wakeup 是 best-effort。即使 websocket wakeup 不可用，每个 runtime 的 poll loop 仍会按配置间隔继续 claim task。

## Claim 与派发

每个已注册 runtime 在 `server/internal/daemon/daemon.go` 里都有自己的 poller。

Poller 会先获取本地并发 slot，再 claim task：

```text
POST /api/daemon/runtimes/{runtimeId}/tasks/claim
```

这个顺序是有意设计的：它避免 task 已经进入服务器侧 `dispatched` 状态，但本地守护进程还没有容量启动它。

服务端处理函数：

```text
server/internal/handler/daemon.go: ClaimTaskByRuntime
```

Claim 响应会返回 task 和执行上下文：

| 数据 | 示例 |
|---|---|
| Task 身份 | `task_id`、`runtime_id`、`workspace_id`、`issue_id`、`chat_session_id`、`autopilot_run_id`。 |
| Agent 配置 | 名称、instructions、skills、custom env、custom args、MCP config、model、thinking level。 |
| 工作区上下文 | 工作区 prompt/context、repos、project resources。 |
| 触发上下文 | 触发评论正文、作者类型/名称、新评论数量、chat message、附件、autopilot payload。 |
| 恢复上下文 | prior session ID 和 prior workdir。 |
| 请求用户上下文 | runtime owner 的名称和 profile description。 |
| Auth | 给被启动 agent 进程使用的 task-scoped `auth_token`。 |

Claim 成功后，守护进程调用：

```text
POST /api/daemon/tasks/{taskId}/start
POST /api/daemon/tasks/{taskId}/progress
```

第一个请求把 task 切到 running。第二个请求广播用户可见的启动进度，例如 `Launching codex`。

## 准备本地执行环境

启动 Codex 之前，`server/internal/daemon/daemon.go` 会把 claim response
里的 task 配置转换成本机可运行的目录、文件、环境变量和 `agent.ExecOptions`。
这个阶段的核心入口是 `Daemon.runTask`，实际落盘逻辑在
`server/internal/daemon/execenv/`。

### `daemon.go` 先做的调度保护

`handleTask` 在调用 `runTask` 之前，会先处理 `local_directory` project
resource：

- 如果 task 绑定了当前 daemon 的 `local_directory`，先解析 resource JSON、校验路径存在且可读写，并避开系统黑名单。
- 对同一个真实路径加本地互斥锁。锁被占用时，daemon 会把 task 标为 `waiting_local_directory`，等待期间继续轮询服务器取消信号。
- 只有拿到锁之后才 `StartTask`，避免服务器状态从 `running` 倒退到等待态。

进入 `runTask` 后，daemon 还会拒绝没有 `workspace_id` 的 task。原因是
`MULTICA_WORKSPACE_ID` 为空时，agent 进程里的 `multica` CLI 可能回退到用户全局配置，误操作到其他 workspace。

### 组装 task 上下文

`runTask` 从 claim response 组装 `execenv.TaskContextForEnv`，主要包括：

- issue、chat、autopilot、quick-create 的任务身份和触发信息
- agent id、agent name、instructions、skills
- workspace context 和请求用户 profile description
- workspace/project/repo 元数据
- trigger comment、new comment count、prior session 是否恢复
- project resources，包括 `github_repo`、`local_directory` 等 resource ref

这份 `taskCtx` 后面会同时用于：

- 写 `.agent_context/issue_context.md`
- 写 `.multica/project/resources.json`
- 生成 Codex 读取的 `AGENTS.md`
- 生成最终发送给 Codex 的 per-turn prompt

### repo 不会在准备阶段直接 checkout

准备执行环境时不会预先 clone 或 checkout repo。`runTask` 只会调用
`registerTaskRepos(task.WorkspaceID, task.Repos)`：

- 把 claim 返回的 task-scoped repo URL 合并进 workspace allowlist。
- 对缓存缺失的 repo 启动后台 sync。
- 让 agent 之后在工作目录里运行 `multica repo checkout <url>` 时能通过本地 daemon health/helper API 创建 git worktree。

真正创建 worktree 的路径是 agent 进程调用 `multica repo checkout` 后，
本地 helper 收到请求，再调用 `repoCache.CreateWorktree`。worktree 会创建在
当前 task 的 `WorkDir` 下，分支名形如 `agent/{agent-name}/{short-task-id}`，
并安装或移除 Co-authored-by hook。

### 选择 workdir：复用、隔离目录或用户目录

`runTask` 会先预测本 task 的 env root：

```text
{WorkspacesRoot}/{workspace_id}/{short(task_id)}
```

并把预测 root 标记为 active，避免 GC 在准备或运行中清理它。

然后分三种情况选择环境：

| 情况 | 行为 |
|---|---|
| 有 `PriorWorkDir` 且不是 `local_directory` | 调用 `execenv.Reuse`，复用同一 agent + issue 上次保存的 workdir，并刷新 context files、Codex home、skills。 |
| 普通 task，无法复用 | 调用 `execenv.Prepare` 创建 `{envRoot}/workdir`、`{envRoot}/output`、`{envRoot}/logs`。 |
| `local_directory` task | 调用 `execenv.Prepare`，但 `WorkDir` 指向用户给定的绝对路径；env root 只放 `output/`、`logs/`、sidecar manifest 等 daemon scratch。 |

`local_directory` 故意不走 reuse：用户目录本来就是稳定路径，但 reuse 会丢失
env root 和 GC/cleanup 关联。重新 prepare 成本低，也更容易保证执行后能清理
Multica 注入的 sidecar。

### `execenv.Prepare` 落盘的通用内容

`execenv.Prepare` 会先删除同 task id 旧 env root，然后创建目录树：

```text
{envRoot}/
  output/
  logs/
  workdir/        # 普通 task 才创建；local_directory 使用用户目录
```

随后在 `WorkDir` 中写入通用上下文：

| 文件或目录 | 用途 |
|---|---|
| `.agent_context/issue_context.md` | 轻量任务摘要、触发方式、quick start、技能列表。 |
| `.multica/project/resources.json` | project resource 的结构化 JSON，供 skill 或工具程序读取。 |
| sidecar manifest | 记录本次 prepare 写过哪些 sidecar 文件/目录，主要用于 `local_directory` 执行后精确清理。 |

对 Codex 来说，workspace-assigned skills 不写入 `WorkDir` 下的
`.agent_context/skills/`。Codex 的 skill discovery 依赖 `CODEX_HOME/skills/`，
所以 skills 会在 Codex 专属步骤写入 per-task `CODEX_HOME`。

### Codex 专属：准备 per-task `CODEX_HOME`

当 provider 是 `codex` 时，`execenv.Prepare` 会创建：

```text
{envRoot}/codex-home/
```

然后把用户共享 Codex home（`$CODEX_HOME`，否则 `~/.codex`）同步到这个
task 专属 home。策略是“认证共享，配置隔离”：

| 内容 | 处理方式 | 原因 |
|---|---|---|
| `sessions/` | symlink 到共享 home | Codex session log 仍写回用户可找到的全局位置。 |
| `auth.json` | symlink 到共享 home；Windows 无法 symlink 时 fallback copy | token 刷新能被 task 看到，避免 per-task copy 过期。 |
| `config.json`、`config.toml`、`instructions.md` | 每次从共享 home 重新 copy | 允许 task 内隔离改写，不污染用户全局配置；reuse 时也能跟上用户新配置。 |
| `plugins/cache/` | symlink 到共享 plugin cache | 避免每个 task 重建 plugin cache。 |

复制 `config.toml` 后，daemon 会做几类 Codex 专属改写：

- 删除继承来的 `[[skills.config]]`。Multica 会直接写 `codex-home/skills/`，用户级 skill registry 在 per-task home 里既冗余，也可能因缺 `path` 被 Codex CLI 严格 TOML 解析拒绝。
- 写入 daemon-managed sandbox block。非 macOS 默认 `workspace-write` + network access；macOS 在当前代码里因为 Codex Seatbelt network bug 会退到 `danger-full-access`，且只改 per-task config。
- 默认禁用 Codex native multi-agent，除非 daemon 环境变量 `MULTICA_CODEX_MULTI_AGENT` 显式开启。原因是 Multica 当前只跟踪父 Codex thread，不能安全等待/取消子 agent。
- 默认禁用 Codex native auto-memory，避免跨 task 或跨 workspace 通过 Codex memory 泄漏上下文。

### Codex skills 的来源和优先级

`hydrateCodexSkills` 会先清空 per-task：

```text
{envRoot}/codex-home/skills/
```

再按顺序写入：

1. 用户本机 `~/.codex/skills/` 里的 user skills。目录 symlink 会先解析成真实目录，再复制普通文件，避免 per-task home 指向外部安装目录。
2. claim response 里的 workspace-assigned skills。

workspace skill 名称和 user skill 名称冲突时，workspace skill 优先。daemon 会跳过同名 user skill，再把 workspace 版本写入干净目录，避免 user skill 的旧支持文件残留。

### Codex `AGENTS.md` runtime brief

准备完目录后，`runTask` 调用：

```text
execenv.InjectRuntimeConfig(env.WorkDir, "codex", taskCtx)
```

对 Codex 来说，这会写或更新：

```text
{WorkDir}/AGENTS.md
```

写入内容是 Multica runtime brief，包含：

- agent identity 和 agent instructions
- requesting user / workspace context
- `multica` CLI 可用命令和工作流
- Codex-specific comment formatting 约束
- 可 checkout 的 repo 列表
- project resources 摘要
- issue metadata 使用规则
- chat、comment-triggered、assignment、autopilot、quick-create 各自的工作流
- skills 列表
- mention、attachment、输出规则

`AGENTS.md` 不是简单覆盖。它用
`<!-- BEGIN MULTICA-RUNTIME ... -->` / `<!-- END MULTICA-RUNTIME -->` marker
插入或替换 daemon 管理块：

- 如果用户 repo 已有 `AGENTS.md`，保留用户内容，只追加/替换 Multica 管理块。
- 如果重复运行同一个 workdir，只替换 marker 内部，避免无限追加。
- 如果是 `local_directory`，执行结束后会调用 `CleanupRuntimeConfig` 删除 marker 块，尽量恢复用户文件原始字节。

### 传给 Codex 进程的环境变量

`runTask` 创建 Codex backend 前，会构造 agent 进程环境：

| 变量 | 含义 |
|---|---|
| `MULTICA_TOKEN` | 优先使用 claim 时服务器铸造的 task-scoped token；旧路径下 fallback 到 daemon token。 |
| `MULTICA_SERVER_URL` | 服务器 API base URL。 |
| `MULTICA_DAEMON_PORT` | 本地守护进程 health/helper 端口。 |
| `MULTICA_WORKSPACE_ID` | 当前工作区。 |
| `MULTICA_AGENT_NAME` | 智能体显示名。 |
| `MULTICA_AGENT_ID` | 智能体 ID。 |
| `MULTICA_TASK_ID` | 当前 task ID。 |
| `MULTICA_TASK_SLOT` | 本地并发 slot index。 |
| `MULTICA_AUTOPILOT_RUN_ID` | autopilot task 时存在。 |
| `MULTICA_AUTOPILOT_ID` | autopilot task 时存在。 |
| `MULTICA_QUICK_CREATE_TASK_ID` | quick-create task 时存在。 |
| `CODEX_HOME` | 准备好的 task 专属 Codex home。 |
| `PATH` | 前置当前 `multica` binary 所在目录。 |

Agent custom env 也会被注入，但守护进程内部保护变量不能被覆盖。

### 生成 `agent.ExecOptions`

最后，`runTask` 会把准备好的环境收束成 `agent.ExecOptions`：

| 字段 | 来源或含义 |
|---|---|
| `Cwd` | `env.WorkDir`，也就是隔离 workdir 或用户 `local_directory`。 |
| `Model` | 优先使用 agent.model，其次使用 daemon 探测配置里的 provider 默认 model。 |
| `ThinkingLevel` | 来自 agent 配置；启动前会用本地 provider model catalog 校验，不合法则跳过注入。 |
| `ResumeSessionID` | claim 返回的 prior session ID。 |
| `ExtraArgs` | daemon/provider 默认参数。 |
| `CustomArgs` | agent 配置里的 custom args。 |
| `McpConfig` | agent 配置里的 MCP config，Codex backend 后续会写入 per-task `$CODEX_HOME/config.toml`。 |
| `Timeout` | daemon 配置的 agent timeout。 |
| `SemanticInactivityTimeout` | Codex 专用语义静默超时。 |

到这里为止，Codex 看到的是：一个 task 专属 `CODEX_HOME`、一个携带
`AGENTS.md` 和 sidecar context 的工作目录、一组 `MULTICA_*` 环境变量、
以及由 `ExecOptions` 指定的 cwd/model/thinking/resume/custom args/MCP 配置。

## 启动 Codex

Codex 执行逻辑位于 `server/pkg/agent/codex.go`。

Backend 会启动本地进程：

```text
codex app-server --listen stdio://
```

Go 调用为：

```go
exec.CommandContext(runCtx, execPath, codexArgs...)
```

守护进程通过该进程的 stdin/stdout 使用 JSON-RPC 与 Codex 通信。生命周期是：

1. `initialize`
2. `initialized`
3. `thread/start` 或 `thread/resume`
4. `turn/start`
5. 等待 `turn/completed`、final answer、取消、超时或错误

当 agent 配置了受管 MCP config 时，Codex MCP config 会写入 task 专属 `$CODEX_HOME/config.toml`。这样可以避免 MCP secret 出现在 argv、系统进程列表和 daemon command log 中。

## 运行消息上报

执行过程中，守护进程会 drain agent message stream，并批量上报：

```text
POST /api/daemon/tasks/{taskId}/messages
```

上报消息字段：

| 字段 | 含义 |
|---|---|
| `seq` | task 内单调递增序号。 |
| `type` | `thinking`、`text`、`tool_use`、`tool_result` 或 `error`。 |
| `tool` | tool 事件的工具名称。 |
| `content` | text/thinking/error 内容。 |
| `input` | tool input JSON。 |
| `output` | tool output 文本；过大时守护进程会先截断。 |

Codex 事件会被归一化为：

| Codex 信号 | Multica message |
|---|---|
| agent message / final answer | `text` |
| command execution started | `tool_use`，`tool = "exec_command"` |
| command execution completed | `tool_result`，`tool = "exec_command"` |
| file change started | `tool_use`，`tool = "patch_apply"` |
| file change completed | `tool_result`，`tool = "patch_apply"` |
| protocol error | `error` |

服务端处理函数：

```text
server/internal/handler/daemon.go: ReportTaskMessages
```

服务器会先对内容做脱敏，再写入 `task_message`，并向连接中的客户端发布 realtime `task:message` 事件。

## Session Pinning

当 Codex 暴露 thread ID 后，守护进程会提前保存 resume 指针：

```text
POST /api/daemon/tasks/{taskId}/session
```

发送数据：

| 字段 | 含义 |
|---|---|
| `session_id` | Codex thread ID。 |
| `work_dir` | task workdir。 |

服务端处理函数：

```text
server/internal/handler/task_lifecycle.go: PinTaskSession
```

这样即使守护进程在最终 complete/fail callback 前崩溃，也不会丢失 resume 上下文。

## Token 用量上报

执行结束后，守护进程会独立于终态上报 token 用量：

```text
POST /api/daemon/tasks/{taskId}/usage
```

Payload：

| 字段 | 含义 |
|---|---|
| `provider` | `codex`。 |
| `model` | 模型名；Codex 未报告时为 `unknown`。 |
| `input_tokens` | 输入 token 数。 |
| `output_tokens` | 输出 token 数。 |
| `cache_read_tokens` | cache read token 数。 |
| `cache_write_tokens` | cache write token 数。 |

服务端处理函数：

```text
server/internal/handler/daemon.go: ReportTaskUsage
```

服务器按 `(task_id, provider, model)` upsert `task_usage`。这张表会支撑 runtime、issue 和 dashboard 的用量查询。

### Codex token 用量如何采集

Multica 不估算 Codex token 用量。守护进程从 Codex runtime 输出里读取。

主路径：Codex JSON-RPC 通知。

在 `codex app-server --listen stdio://` session 中，`codexClient` 会监听 turn/event payload 中的这些字段：

- `usage`
- `token_usage`
- `tokens`

由于 Codex 不同版本的 payload 命名不同，Multica 接受多种 key：

| Multica 字段 | 接受的 Codex key |
|---|---|
| `input_tokens` | `input_tokens`、`input`、`prompt_tokens` |
| `output_tokens` | `output_tokens`、`output`、`completion_tokens` |
| `cache_read_tokens` | `cache_read_tokens`、`cache_read_input_tokens` |
| `cache_write_tokens` | `cache_write_tokens`、`cache_creation_input_tokens` |

这些值会累加到当前 task 的内存态 Codex client 里。

Fallback 路径：Codex session JSONL 文件。

如果 JSON-RPC 通知没有给出 input/output token，守护进程会扫描 task 开始之后写入的 Codex session log：

```text
$CODEX_HOME/sessions/YYYY/MM/DD/*.jsonl
~/.codex/sessions/YYYY/MM/DD/*.jsonl
```

它会查找：

- `turn_context` 事件，用于恢复模型名
- `token_count` 事件，用于恢复 token 用量

对于 `token_count`，scanner 优先使用 `total_token_usage`，否则 fallback 到 `last_token_usage`。映射字段包括：

- `input_tokens`
- `output_tokens + reasoning_output_tokens`
- `cached_input_tokens` 或 `cache_read_input_tokens`

如果最终仍不知道模型名，守护进程会上报 `model = "unknown"`。

## 运行时与 agent 配置信息来源

配置流向分两类：

1. 本地守护进程和 runtime capability discovery 从用户电脑流向服务器。
2. Agent/task 执行配置在 claim task 时从服务器流向守护进程。

### 本地 runtime discovery

守护进程启动时，`LoadConfig` 会读取 CLI flags、环境变量和本地 executable discovery 结果。

重要本地 runtime 配置包括：

| 配置 | 来源 |
|---|---|
| server URL | `MULTICA_SERVER_URL` 或 CLI override |
| daemon ID | 本地 profile state / `MULTICA_DAEMON_ID` / CLI override |
| device name | `MULTICA_DAEMON_DEVICE_NAME` / CLI override |
| runtime display name | `MULTICA_AGENT_RUNTIME_NAME` / CLI override |
| workspaces root | `MULTICA_WORKSPACES_ROOT` / CLI override |
| heartbeat interval | `MULTICA_DAEMON_HEARTBEAT_INTERVAL` / CLI override |
| poll interval | `MULTICA_DAEMON_POLL_INTERVAL` / CLI override |
| max concurrency | `MULTICA_DAEMON_MAX_CONCURRENT_TASKS` / CLI override |
| task timeout | `MULTICA_AGENT_TIMEOUT` / CLI override |
| Codex semantic inactivity timeout | `MULTICA_CODEX_SEMANTIC_INACTIVITY_TIMEOUT` / CLI override |
| Codex executable | `MULTICA_CODEX_PATH`、PATH、login shell PATH 或 Codex Desktop bundle |
| Codex default model | `MULTICA_CODEX_MODEL` |
| Codex default args | daemon-level Codex args |

Runtime registration 只向服务器发送本地 capability 的摘要：provider、runtime name、runtime version、device info、CLI version 和 launch source。完整本地环境变量不会作为 runtime registration metadata 上传。

### 服务端发起的 runtime metadata 请求

部分 runtime 信息由服务器懒加载请求，再通过 heartbeat 的 pending action 交给守护进程处理。

Model list 流程：

1. 用户/API 调用 `POST /api/runtimes/{runtimeId}/models`。
2. 服务器入队一个 pending model-list request。
3. 某次 daemon heartbeat 响应包含 `pending_model_list`。
4. 守护进程调用 `agent.ListModels(ctx, provider, executablePath)`。
5. 守护进程回报：

```text
POST /api/daemon/runtimes/{runtimeId}/models/{requestId}/result
```

对 Codex 来说，`ListModels` 从静态 Codex model catalog 开始，并在可能时通过本地 Codex CLI 补充 thinking-level 支持。Discovery 失败不会阻塞 task 执行，只是 UI 可能不会展示 per-model thinking-level 选项。

Local skills 流程：

1. 用户/API 调用 `POST /api/runtimes/{runtimeId}/local-skills`。
2. 服务器入队一个 pending local-skill list request。
3. Heartbeat 返回 `pending_local_skills`。
4. 守护进程列出 provider 对应的本地 skill。
5. 守护进程回报：

```text
POST /api/daemon/runtimes/{runtimeId}/local-skills/{requestId}/result
```

Local skill import 也使用同样的 heartbeat request/result 模式：

```text
POST /api/daemon/runtimes/{runtimeId}/local-skills/import/{requestId}/result
```

### 来自服务器的 task 执行配置

Claim 时，服务器会返回守护进程运行 Codex 所需的 task-specific config，包括：

- agent instructions
- skills 和 skill files
- agent custom env
- agent custom args
- agent MCP config
- selected model
- selected thinking level
- workspace context
- project resources
- repo list
- trigger comment/chat/autopilot context
- prior session ID 和 prior workdir
- task-scoped auth token

守护进程会组合：

- `LoadConfig` 得到的本地 runtime 配置
- registration/model discovery 得到的 runtime capability
- `ClaimTaskByRuntime` 返回的 task config
- `execenv` 生成的 execution-context files

最终这些信息会通过以下方式传给 Codex：

- process argv：`codex app-server --listen stdio://`，以及过滤后的 daemon 和 agent custom args
- process environment：`MULTICA_*`、`CODEX_HOME`、PATH 和允许的 custom env
- workdir files：`AGENTS.md`、skills、`.agent_context`、sidecar manifests
- JSON-RPC：thread start/resume、model 与 thinking-level config、turn input prompt

## 终态结果上报

成功完成：

```text
POST /api/daemon/tasks/{taskId}/complete
```

Payload：

| 字段 | 含义 |
|---|---|
| `output` | agent 最终输出。 |
| `session_id` | Codex thread ID。 |
| `work_dir` | task 使用的 workdir。 |

服务端处理函数：

```text
server/internal/handler/daemon.go: CompleteTask
```

失败、超时、blocked 或 resume-unsafe 终态：

```text
POST /api/daemon/tasks/{taskId}/fail
```

Payload：

| 字段 | 含义 |
|---|---|
| `error` | 用户可见失败文本。 |
| `session_id` | 可用时为 Codex thread ID。 |
| `work_dir` | task 使用的 workdir。 |
| `failure_reason` | 稳定分类，例如 `timeout`、`idle_watchdog` 或 provider-specific resume-unsafe reason。 |

服务端处理函数：

```text
server/internal/handler/daemon.go: FailTask
```

Complete 和 fail 路径在终态 callback 成功后都会撤销 task-scoped token。

## 取消与 Watchdog

Codex 运行期间，守护进程会轮询 task 状态：

```text
GET /api/daemon/tasks/{taskId}/status
```

如果服务器报告 task 已取消，或 task row 已消失，守护进程会取消 Codex 进程 context，并丢弃本地结果。

本地还有多层保护：

- 整体 agent timeout
- Codex semantic inactivity timeout
- first-turn no-progress timeout
- backend 静默且无 in-flight tool call 时的 idle watchdog

Watchdog 导致 blocked/fail 终态时，token usage 仍会先上报，再执行终态 callback。

## 服务端存储汇总

| 数据 | Endpoint | 主要存储/效果 |
|---|---|---|
| Runtime registration | `/api/daemon/register` | Upsert `agent_runtime`；发出 runtime analytics。 |
| Heartbeat | `/api/daemon/heartbeat` | 更新 runtime freshness，并返回 pending daemon actions。 |
| Claim | `/api/daemon/runtimes/{runtimeId}/tasks/claim` | 将 queued task 移到 dispatched，并返回执行上下文。 |
| Start | `/api/daemon/tasks/{taskId}/start` | 将 task 移到 running。 |
| Progress | `/api/daemon/tasks/{taskId}/progress` | 通过 task service/realtime 广播进度。 |
| Messages | `/api/daemon/tasks/{taskId}/messages` | 脱敏并插入 `task_message`；发布 realtime events。 |
| Session | `/api/daemon/tasks/{taskId}/session` | 更新 task `session_id` 和 `work_dir`。 |
| Usage | `/api/daemon/tasks/{taskId}/usage` | Upsert `task_usage`。 |
| Complete | `/api/daemon/tasks/{taskId}/complete` | 标记 task complete，存储结果，撤销 task token。 |
| Fail | `/api/daemon/tasks/{taskId}/fail` | 标记 task failed/blocked，存储错误元数据，撤销 task token。 |

## 隐私与信任边界

- 当服务器能铸造 task-scoped token 时，被启动的 Codex 进程会收到该 token。这限制了 agent 相比 daemon owner 完整凭据能做的事情。
- Agent custom env 只在本地注入，且不能覆盖 daemon-protected keys。
- 受管 Codex MCP config 写入 `$CODEX_HOME/config.toml`，不会放进 argv，避免泄漏到进程列表和日志。
- Task messages 在服务端持久化和 realtime 广播前会先脱敏。
- 特别大的 tool output 在上报前会被守护进程截断。
- 服务器没有直接控制用户电脑进程的能力；所有进程执行都由本地守护进程在认证 claim 后发起。
