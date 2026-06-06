# Daemon 获取并处理云端 Skills 的实现分析

本文分析当前项目里，本地 daemon 进程拿到“云端 skills 信息”后的完整处理链路，并补充说明一个容易混淆的旁路: `runtime local skills`。

## 先说结论

当前代码里，daemon 并不会先去“拉一个 workspace skills 列表”再自己筛选。

真正的主流程是:

1. daemon 为某个 runtime 轮询任务。
2. daemon 调用服务端 `POST /api/daemon/runtimes/{runtimeId}/tasks/claim`。
3. 服务端在 claim 响应里直接把这个 task 对应 agent 的 `instructions`、`skills`、`skill files` 一并打包返回。
4. daemon 收到后，不是只放在内存里，而是进一步写入任务工作目录:
   - 写到 provider 原生可发现的 skills 目录
   - 同时写 `AGENTS.md` / `CLAUDE.md` / `GEMINI.md` 之类的运行时说明文件
5. 之后真正启动 Codex / Claude / Copilot / OpenCode 等 agent CLI，让它们从这些本地落地文件里发现并使用 skills。

也就是说，**云端 skill -> daemon 的关键边界不是 heartbeat，而是 claim task 响应**。

## 两条不同的 skills 链路

项目里和 skill 相关的 daemon 逻辑其实有两条，语义不同:

### 1. 云端 workspace skills 下发给 daemon 执行

这是你问题里最核心的链路。

- skill 数据来源: 服务端数据库 `skill` / `skill_file` / `agent_skill`
- 触发点: daemon claim 到一个 task
- 作用: 给本次任务执行环境注入 agent 当前绑定的云端 skills

### 2. daemon 扫描“本机 local skills”并回传服务端

这是另一条链路，不是“云端下发”，而是“本地发现 -> 上报 -> 可选导入到云端 workspace skill”。

- skill 数据来源: 本机磁盘，如 `~/.codex/skills`、`~/.claude/skills` 等
- 触发点: 用户调用 `/api/runtimes/{runtimeId}/local-skills*`
- 作用: 让服务端知道这个 runtime 本地有哪些 skill，或把其中某个 skill 导入成 workspace skill

后面会先讲主链路，再讲旁路。

## 主链路: 云端 skills 如何进入 daemon

### 1. daemon 通过 claim 接口向服务端要任务

daemon 客户端在 `server/internal/daemon/client.go` 里通过:

- `ClaimTask(ctx, runtimeID)` -> `POST /api/daemon/runtimes/{runtimeId}/tasks/claim`

实现位置:

- `server/internal/daemon/client.go`

这个接口返回的不是“只有 task id”，而是完整的 `Task` 结构，其中包含:

- `Task.Agent`
- `Task.Agent.Instructions`
- `Task.Agent.Skills`
- `Task.Agent.CustomEnv`
- `Task.Agent.CustomArgs`

daemon 侧接收结构定义在:

- `server/internal/daemon/types.go`

其中关键字段是:

- `Task.Agent *AgentData`
- `AgentData.Skills []SkillData`
- `SkillData.Content`
- `SkillData.Files []SkillFileData`

这说明 daemon 期望服务端直接把 skill 正文和附属文件都下发下来，而不是只给一个 skill id。

### 2. 服务端 claim handler 在响应里装配 agent skills

接口路由定义在:

- `server/cmd/server/router.go`

对应 handler:

- `server/internal/handler/daemon.go`
- `ClaimTaskByRuntime`

在 `ClaimTaskByRuntime` 中，服务端先 claim task，然后开始构造返回体:

1. `resp := taskToResponse(*task)`
2. 查询 agent 基本信息 `GetAgent`
3. 调用 `h.TaskService.LoadAgentSkills(...)`
4. 将 skills 填入 `resp.Agent`

关键代码职责:

- `server/internal/handler/daemon.go`
  - 构造 `resp.Agent`
  - 将 `Instructions`、`Skills`、`CustomEnv`、`CustomArgs`、`McpConfig` 一并放入 claim 响应
- `server/internal/handler/agent.go`
  - `TaskAgentData` 定义了 claim 返回给 daemon 的 agent 结构

### 3. 服务端如何从数据库读取 skill 内容

真正把 skills 从数据库装出来的是:

- `server/internal/service/task.go`
- `TaskService.LoadAgentSkills`

它的逻辑很直接:

1. `ListAgentSkills(agentID)` 查出该 agent 绑定的所有 skill
2. 对每个 skill 再调用 `ListSkillFiles(skill.ID)` 查附属文件
3. 组装成 `AgentSkillData`

对应 SQL 在:

- `server/pkg/db/queries/skill.sql`

关键表关系:

- `skill`
  - 保存 skill 主体
  - `content` 对应主 `SKILL.md` 内容
- `skill_file`
  - 保存 skill 附属文件
- `agent_skill`
  - agent 与 skill 的绑定关系

所以 claim 响应里的 skill 数据来源是:

1. `agent_skill` 找出 agent 绑定了哪些 skill
2. `skill.content` 提供主 skill 正文
3. `skill_file` 提供 supporting files

### 4. daemon 拿到 claim 响应后，先转成执行上下文

daemon 真正开始执行任务时，入口在:

- `server/internal/daemon/daemon.go`
- `runTask(...)`

这里会从 `task.Agent` 中取出:

- `agentName`
- `instructions`
- `skills`

然后组装 `execenv.TaskContextForEnv`:

- `AgentInstructions: instructions`
- `AgentSkills: convertSkillsForEnv(skills)`

其中 `convertSkillsForEnv` 会把 daemon 层的:

- `SkillData`
- `SkillFileData`

转成 execenv 层的:

- `SkillContextForEnv`
- `SkillFileContextForEnv`

也就是说，从这一步开始，skills 已经从“HTTP 返回 JSON”变成“要落地到本地执行目录的上下文对象”。

### 5. daemon 把 skills 写入任务工作目录

任务本地环境由 `execenv` 负责:

- `server/internal/daemon/execenv/execenv.go`
- `Prepare(...)`
- `Reuse(...)`

两条路径都会调用:

- `writeContextFiles(workDir, provider, ctx)`

实现位置:

- `server/internal/daemon/execenv/context.go`

这里做了三件事:

1. 写 `.agent_context/issue_context.md`
2. 解析 provider 对应的 skills 目录
3. 把每个 skill 写成真实目录和文件

#### provider-specific skills 目录

`resolveSkillsDir` 会根据 provider 选择不同落盘位置，例如:

- Claude: `{workDir}/.claude/skills`
- Copilot: `{workDir}/.github/skills`
- OpenCode: `{workDir}/.opencode/skills`
- OpenClaw: `{workDir}/skills`
- Pi: `{workDir}/.pi/skills`
- Cursor: `{workDir}/.cursor/skills`
- Kimi: `{workDir}/.kimi/skills`
- Kiro: `{workDir}/.kiro/skills`
- 默认回退: `{workDir}/.agent_context/skills`

#### skill 文件如何写

`writeSkillFiles` 的行为是:

1. 用 `sanitizeSkillName(skill.Name)` 生成目录名
2. 在该目录下写 `SKILL.md`
3. 再把附属文件按原路径写进去

如果云端 skill 的 `content` 没有合法 frontmatter，`ensureSkillFrontmatter` 会自动补一个，避免某些 runtime 因 frontmatter 不完整而忽略这个 skill。

因此，云端 skill 到本地磁盘后的结构大致如下:

```text
<workDir>/
  .opencode/skills/<skill-slug>/SKILL.md
  .opencode/skills/<skill-slug>/<supporting-files...>
```

或:

```text
<workDir>/
  .agent_context/skills/<skill-slug>/SKILL.md
  .agent_context/skills/<skill-slug>/<supporting-files...>
```

具体取决于 provider。

### 6. daemon 再写运行时元说明文件，让 agent 知道 skill 怎么被发现

仅把 skill 文件写到磁盘还不够，daemon 还会写一份“元说明文件”:

- Claude -> `CLAUDE.md`
- Codex / Copilot / OpenCode / OpenClaw / Pi / Cursor / Kimi / Kiro -> `AGENTS.md`
- Gemini -> `GEMINI.md`

实现位置:

- `server/internal/daemon/execenv/runtime_config.go`
- `InjectRuntimeConfig`

这份文件会包含:

- agent identity
- agent instructions
- skills 列表
- Multica CLI 使用约束
- issue / comment / mention / attachment 等运行时说明

对于 skills，这份文件不会把每个 skill 正文都全文内嵌进去，而是告诉 runtime:

- “你已经安装了以下 skills”
- 或 “详细 skill 在 `.agent_context/skills/` 下”

换句话说:

- **skill 实体内容** 在 provider-native 目录里
- **skill 如何被 agent 使用** 由 `AGENTS.md` / `CLAUDE.md` 指路

### 7. Codex 是一个特殊分支

Codex 不走普通 `writeContextFiles` 的 skill 落地路径，而是额外构建:

- `codex-home`
- 设置环境变量 `CODEX_HOME`

实现位置:

- `server/internal/daemon/execenv/execenv.go`
- `prepareCodexHomeWithOpts`
- `hydrateCodexSkills`

`runTask` 里会把:

- `agentEnv["CODEX_HOME"] = env.CodexHome`

传给子进程。

这意味着对 Codex 来说，workspace skills 最终会被写进这个任务专属的 `CODEX_HOME` 中，让 Codex 按自己的原生技能发现机制加载，而不会污染用户全局的 `~/.codex/skills`。

## 这条主链路的时序图

```text
daemon poller
  -> POST /api/daemon/runtimes/:runtimeId/tasks/claim
server ClaimTaskByRuntime
  -> claim task
  -> GetAgent
  -> LoadAgentSkills
       -> ListAgentSkills
       -> ListSkillFiles
  -> 返回 task.agent.instructions + task.agent.skills
daemon runTask
  -> convertSkillsForEnv
  -> execenv.Prepare / Reuse
  -> writeContextFiles
  -> writeSkillFiles
  -> InjectRuntimeConfig
  -> 启动 Codex / Claude / ...
agent runtime
  -> 从本地目录发现 skills
```

## 旁路: daemon 的 local skills 是如何和服务端交互的

这条链路不是“云端 skill 下发”，但和你看到的 `daemon.go` heartbeat 逻辑强相关。

### 1. 用户先从业务 API 发起请求

路由在:

- `POST /api/runtimes/{runtimeId}/local-skills`
- `GET /api/runtimes/{runtimeId}/local-skills/{requestId}`
- `POST /api/runtimes/{runtimeId}/local-skills/import`
- `GET /api/runtimes/{runtimeId}/local-skills/import/{requestId}`

实现位置:

- `server/internal/handler/runtime_local_skills.go`

服务端不会直接执行本地扫描，而是把请求放进 store:

- `LocalSkillListStore`
- `LocalSkillImportStore`

状态机会经历:

- `pending`
- `running`
- `completed`
- `failed`
- `timeout`

### 2. daemon heartbeat 时被动领取待处理请求

daemon heartbeat:

- `POST /api/daemon/heartbeat`

服务端 `processHeartbeat(...)` 会检查:

- `PendingLocalSkills`
- `PendingLocalSkillImport`
- `PendingLocalSkillImports`

协议定义在:

- `server/pkg/protocol/messages.go`

如果有待处理请求，服务端不会主动推完整 skill 内容，而只会在 heartbeat ack 中返回:

- request id
- `skill_key`（import 时）

### 3. daemon 在本地扫描或读取 skill bundle

daemon 收到 heartbeat ack 后，在:

- `server/internal/daemon/daemon.go`

里触发:

- `handleLocalSkillList(...)`
- `handleLocalSkillImport(...)`

它们调用:

- `listRuntimeLocalSkills(provider)`
- `loadRuntimeLocalSkillBundle(provider, skillKey)`

实现位置:

- `server/internal/daemon/local_skills.go`

这里扫描的是本地 runtime 对应的 skill 根目录，比如:

- `~/.claude/skills`
- `~/.codex/skills`
- `~/.copilot/skills`
- `~/.config/opencode/skills`

### 4. daemon 再把结果回传给服务端

daemon 通过:

- `POST /api/daemon/runtimes/{runtimeId}/local-skills/{requestId}/result`
- `POST /api/daemon/runtimes/{runtimeId}/local-skills/import/{requestId}/result`

回传结果。

daemon 侧实现:

- `ReportLocalSkillListResult`
- `ReportLocalSkillImportResult`

如果是 import，服务端 `ReportLocalSkillImportResult(...)` 会进一步:

1. 读取 daemon 上报的 skill bundle
2. 可选覆盖 name / description
3. 调用 `createSkillWithFiles(...)`
4. 把它落成一个真正的 workspace cloud skill
5. 写入 `config.origin.type = runtime_local`

因此这条链路的方向是:

```text
本机 local skill -> daemon -> 服务端 -> workspace skill
```

而不是:

```text
云端 skill -> daemon
```

## 为什么 heartbeat 里看不到云端 skills 下发

因为 heartbeat 负责的是“异步动作队列”，不是“任务执行上下文”。

heartbeat 里现在承载的主要是:

- CLI update
- model list
- local skill list
- local skill import

这些动作的共同特点是:

- 不绑定某个 task
- 可以异步排队
- daemon 完成后再单独上报结果

而云端 workspace skills 属于“某个 task 的执行上下文”，天然跟 claim task 绑定，因此放在 `ClaimTaskByRuntime` 的响应里最合理。

## 关键设计含义

### 1. daemon 不维护全量云端 skill 缓存

它只在 claim task 时拿当前任务需要的 agent skills。

好处:

- 不需要 daemon 自己做 workspace 全量同步
- agent 改绑 skill 后，下一次 claim 自然拿到最新数据
- 避免本地缓存失效问题

### 2. skill 是“执行期物化”到本地磁盘

服务端存的是数据库记录，daemon 执行时才把它们写成:

- `SKILL.md`
- supporting files
- provider-native skills tree

这一步是必须的，因为各类 agent CLI 最终消费的是本地文件系统，不是 Multica 的数据库行。

### 3. claim 响应承担了“执行快照”角色

当 task 被 claim 时，agent instructions、skills、custom env、project resources 一起被打包成一次执行快照。

这意味着:

- 任务一旦 claim，daemon 后续执行主要依赖本地落地的这份上下文
- 如果中途服务端 skill 被修改，不会自动热更新到已启动任务
- 新任务 claim 才会看到新的 skill 内容

## 一句话总结

当前项目中，“daemon 获取云端 skills 信息后如何处理”的核心实现不是 heartbeat 拉 catalog，而是:

**claim task 时由服务端把 agent 绑定的 workspace skills 连同文件内容一并返回，daemon 再把这些 skill 物化到任务工作目录和 provider 原生技能目录中，最后让 Codex/Claude 等本地 CLI 按原生机制发现并执行。**

而 `local-skills` 相关代码走的是另一条相反方向的链路:

**daemon 扫描本机 skills，再通过 heartbeat 驱动的异步 result 接口回传给服务端。**

## 补充: 如何理解“任务工作目录”

### 它是什么

“任务工作目录”不是工作区配置里的一个业务字段，而是 daemon 在本机为一次 task 执行自动创建的本地执行目录。

代码上有两层:

1. `WorkspacesRoot`
   - daemon 所有任务执行环境的根目录
   - 来自 `MULTICA_WORKSPACES_ROOT` 或默认值
2. `WorkDir`
   - 某个具体 task 实际运行时的 cwd
   - 位于该 task 的 env root 下的 `workdir/`

目录结构大致如下:

```text
<MULTICA_WORKSPACES_ROOT>/
  <workspace-id>/
    <short-task-id>/
      workdir/
      output/
      logs/
      codex-home/   # 仅部分 provider 存在
```

这意味着:

- `MULTICA_WORKSPACES_ROOT` 是“所有任务环境的总根目录”
- `workdir/` 才是 agent 子进程真正启动时的当前工作目录

### 它从哪里来

daemon 配置里有:

- `WorkspacesRoot string`

解析顺序是:

1. CLI override
2. 环境变量 `MULTICA_WORKSPACES_ROOT`
3. 默认值
   - 默认 profile: `~/multica_workspaces`
   - 命名 profile: `~/multica_workspaces_<profile>`

某个 task 的 env root 则由 daemon 自动拼出来:

```text
<WorkspacesRoot>/<workspaceID>/<short(taskID)>
```

再在下面创建:

```text
workdir/
output/
logs/
```

### 为什么运行时设置页面里看不到

因为这不是服务端 runtime 资源的业务属性，而是 daemon 本机部署参数。

运行时设置页面配置的是:

- provider / model / thinking level
- runtime 在线状态相关能力
- agent 的 custom env / custom args

但 task workdir 放在哪块本地磁盘，是 daemon 所在机器自己的执行环境策略，不适合由服务端 UI 统一管理。

## 实际场景: 任务 100 要读取本地路径 B，skills 会从哪里取

假设:

- 你已经配置了一个 runtime
- daemon 的 `MULTICA_WORKSPACES_ROOT=/Users/me/multica_workspaces`
- task id 是 `100`
- 任务内容要求 agent 去读取本地路径 `B`

### 先看 agent 真正从哪里启动

daemon 启动 agent 时，会把 `ExecOptions.Cwd` 固定设置为:

- `env.WorkDir`

也就是 task workdir，而不是 `B`。

所以在默认情况下，agent 的工作目录是类似这样的路径:

```text
/Users/me/multica_workspaces/<workspace-id>/<short-task-id>/workdir
```

而不是:

```text
B
```

### skills 会从 B 下的 `.skills` 读吗

**默认不会。**

仅仅因为任务要求“去读取路径 B 下的文件”，并不会让 runtime 把 skill 发现根切换到 `B`。

当前实现里，skills 的发现根仍然是 daemon 为这个 task 准备的那套目录:

- Claude: `{workDir}/.claude/skills`
- Copilot: `{workDir}/.github/skills`
- OpenCode: `{workDir}/.opencode/skills`
- OpenClaw: `{workDir}/skills`
- Pi: `{workDir}/.pi/skills`
- Cursor: `{workDir}/.cursor/skills`
- Kimi: `{workDir}/.kimi/skills`
- Kiro: `{workDir}/.kiro/skills`
- 默认回退: `{workDir}/.agent_context/skills`
- Codex: 特殊，走 per-task `CODEX_HOME/skills`

注意这里也不是直接从 `MULTICA_WORKSPACES_ROOT` 根目录读，而是从它下面的**当前 task workdir / task-scoped codex-home** 读。

所以更准确地说:

- 不是从 `B/.skills` 读
- 也不是粗粒度地从 `MULTICA_WORKSPACES_ROOT` 根直接读
- 而是从 **`MULTICA_WORKSPACES_ROOT` 下这个 task 自己的执行环境目录** 读

### 那如果 B 本身是一个代码仓库呢

如果 agent 通过:

- `multica repo checkout <url>`

把代码 checkout 到 task workdir 下面，那么 repo worktree 也是落在 task workdir 里，而不是跳到一个外部任意目录。

也就是说，Multica 的正常代码工作流仍然围绕 task workdir 展开:

```text
task workdir
  ├─ provider-native skills
  ├─ AGENTS.md / CLAUDE.md
  └─ checked out repo worktree
```

### 什么情况下 B 会影响 skill 发现

只有在 runtime 自身被显式改成“以 B 作为它的项目工作目录/扫描根”时，B 才可能影响 skills 发现。

但在当前 Multica daemon 实现中，agent 进程启动时的 cwd 是 daemon 传入的 `env.WorkDir`，不是任务文本里提到的任意路径 `B`。因此:

- “读取 B”只是一个文件访问动作
- 它不会自动改变 skill discovery root

## 用一句话回答这个问题

如果 task 100 要求 agent 读取本地路径 `B`，**当前实现下 runtime 默认仍然从这个 task 的执行环境读取 skills，而不是从 `B/.skills` 读取**。

更具体地说，skills 读取位置是:

- 大多数 provider: `MULTICA_WORKSPACES_ROOT/<workspace-id>/<short-task-id>/workdir/...`
- Codex: `MULTICA_WORKSPACES_ROOT/<workspace-id>/<short-task-id>/codex-home/skills`

而不是“谁被读取，就把谁当作 skills 根目录”。

## 如果你就是想让它去 B 里面读，当前有哪些办法

先给结论:

- **当前实现没有现成配置可以让某个 task 直接以现有本地目录 `B` 作为启动 cwd。**
- 所以也就没有现成机制让 provider 原生去扫描 `B` 下的 project-level skills。

### 为什么当前做不到

当前 daemon 的控制面是固定的:

1. daemon 为 task 创建自己的 `env.WorkDir`
2. 启动 agent 子进程时把 `cwd` 固定设为 `env.WorkDir`
3. 对 OpenCode 这类 provider，还会显式用 `--dir <workDir>` 和 `PWD=<workDir>` 把扫描根锚死到 task workdir
4. 对 Codex，还会把 `CODEX_HOME` 指到 task 私有目录

所以从当前实现看:

- `B` 只是“任务里提到的一个本地路径”
- 它不是 daemon 认可的“执行根”
- 它也不会自动变成 skill discovery root

### 方案 1: 不改代码，改成“把 B 的 skill 内容搬进 daemon 管控环境”

这是当前最现实的做法。

可选方式有三种:

#### 1.1 把 B 里的能力整理成 workspace skill

如果 `B` 里的 `.skills` 本质上是一些说明、脚本、模板，那最稳妥的方式是:

1. 把它整理成一个标准 `SKILL.md` + supporting files
2. 导入到 Multica 的 workspace skill
3. 绑定到对应 agent

这样 daemon 在 claim task 时会自动下发，并物化到 task workdir。

优点:

- 跟当前架构完全一致
- 跨机器、跨 daemon 一致
- 不依赖某台机器本地必须存在 `B`

#### 1.2 如果是 Codex，用用户级 `~/.codex/skills`

Codex 有一个特例:

- daemon 会把共享 `~/.codex/skills` 里的用户级 skills 复制到 per-task `CODEX_HOME/skills`

所以如果你的目标只是“让 Codex 在每次任务里都能看到某些本地 skill”，可以把这些 skill 安装到:

```text
~/.codex/skills/
```

而不是放在 `B/.skills`。

这不等于让它“从 B 读”，但能达到“任务执行时看到这些本地 skill”的效果。

#### 1.3 在 task 启动前把 B 的内容同步/链接到 task workdir

如果你控制 daemon 所在机器，也可以在 daemon 外部做一层预同步，把:

- `B/.claude/skills`
- `B/.opencode/skills`
- `B/.github/skills`

之类的内容复制或软链到 task workdir 的 provider-native 位置。

例如语义上做成:

```text
task workdir/.opencode/skills  ->  B/.opencode/skills
```

但这不是当前仓库内建能力，需要你自己在 daemon 外围做自动化。

注意:

- 这类方案依赖 B 在本机存在
- 路径耦合强
- 多任务并发、符号链接一致性、权限问题都要自己处理

### 方案 2: 改代码，让 task 真正以 B 作为执行根

如果你的真实需求是:

> 这个 runtime 在执行 task 100 时，应该直接在已有项目目录 B 中启动，并让 provider 按 B 的项目结构发现 skills

那就需要改 daemon。

最直接的改法是引入一个明确的“执行目录覆盖”能力，例如:

- task / project resource / runtime 配置里增加一个 `working_dir_override`
- claim 时服务端把这个字段返回给 daemon
- daemon 在 `runTask` 中：
  - 不再总是使用 `env.WorkDir`
  - 而是根据该字段决定真实 `cwd`
  - 并同步调整 provider-specific bootstrap

#### 至少要改哪些位置

1. `server/internal/daemon/daemon.go`
   - `runTask` 里 `execOpts.Cwd` 现在固定是 `env.WorkDir`
   - 这里要改成“可切换到 B”

2. `server/internal/daemon/execenv/context.go`
   - 现在 skills 文件写到 task workdir 对应目录
   - 如果真实执行目录变成 B，就要决定:
     - 是把 skill 也写进 B
     - 还是继续写 task workdir，再让 provider 指向那里

3. provider 适配层
   - OpenCode 当前用 `--dir <workDir>` 明确锚定
   - OpenClaw 当前靠 per-task config 把 workspace 固定到 `workDir`
   - Codex 当前靠 per-task `CODEX_HOME`
   - 这些都要检查是否应该随 B 一起切换

4. 安全边界
   - 不能允许任意 task 指向宿主机任意路径
   - 最少要做 allowlist / ownership / existence / symlink 校验

#### 这个改法的风险

这不是小改动，因为它会改变 daemon 的一个核心假设:

> task 的执行环境由 daemon 完全托管，并且围绕 task workdir 组织

一旦直接跑到 B:

- GC 就不再只管理自己的 env root
- repo checkout 语义会变化
- 多个 task 可能并发写同一个 B
- project-level skills 与 workspace skills 的优先级会变复杂

所以如果只是想“复用 B 里的规则”，优先别走这个方向。

## 实操建议

如果你的目标只是“让任务执行时能用到 B 里的那套 skills / 规则”，推荐优先级如下:

1. 最推荐: 把 B 里的规则整理成 workspace skill
2. 如果只跑 Codex: 安装到 `~/.codex/skills`
3. 如果你必须保留 B 作为项目根语义: 改 daemon，显式支持 `working_dir_override=B`

## 一句话结论

当前实现下，**不能靠“任务里提到路径 B”自动让 runtime 从 B 读 skills**。

想让它“去 B 里面读”，本质上只有两条路:

1. **把 B 的 skills 内容搬到 daemon 当前会读的位置**
2. **改 daemon，让 agent 真正在 B 里启动**
