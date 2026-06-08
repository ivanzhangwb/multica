package ai.multica.server.daemon;

import ai.multica.server.common.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class DaemonService {
    private final DaemonRepository repository;

    DaemonService(DaemonRepository repository) {
        this.repository = repository;
    }

    DaemonModels.RegisterResponse register(DaemonModels.RegisterRequest request) {
        String workspaceId = require(request.workspaceId(), "workspace_id is required");
        String daemonId = require(request.daemonId(), "daemon_id is required");
        List<DaemonModels.RuntimeInput> requestedRuntimes = DaemonModels.copyList(request.runtimes());
        if (requestedRuntimes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "runtimes is required");
        }

        List<DaemonModels.RuntimeView> runtimes = requestedRuntimes.stream()
                .map(runtime -> repository.upsertRuntime(workspaceId, daemonId,
                        defaultString(runtime.name(), runtime.type()), require(runtime.type(), "runtime type is required"),
                        defaultString(runtime.status(), "online"), request.cliVersion()))
                .map(this::runtimeView)
                .toList();
        WorkspaceResources resources = repository.workspaceResources(workspaceId);
        return new DaemonModels.RegisterResponse(runtimes, resources.repos(), resources.reposVersion(), resources.settings());
    }

    void deregister(List<String> runtimeIds) {
        repository.markRuntimesOffline(DaemonModels.copyList(runtimeIds));
    }

    DaemonModels.HeartbeatResponse heartbeat(DaemonModels.HeartbeatRequest request) {
        String runtimeId = require(request.runtimeId(), "runtime_id is required");
        RuntimeRecord runtime = runtime(runtimeId);
        repository.recordHeartbeat(runtime.id(), Instant.now());
        return new DaemonModels.HeartbeatResponse("ok", false, null, null, null, null, null);
    }

    DaemonModels.WorkspaceReposResponse workspaceRepos(String workspaceId) {
        WorkspaceResources resources = repository.workspaceResources(workspaceId);
        return new DaemonModels.WorkspaceReposResponse(resources.workspaceId(), resources.repos(), resources.reposVersion(),
                resources.settings());
    }

    DaemonModels.ClaimResponse claim(String runtimeId) {
        runtime(runtimeId);
        return new DaemonModels.ClaimResponse(repository.claimNextTask(runtimeId).map(this::taskView).orElse(null));
    }

    DaemonModels.StatusResponse taskStatus(String taskId) {
        return new DaemonModels.StatusResponse(task(taskId).status().wireName());
    }

    void startTask(String taskId) {
        TaskRecord task = task(taskId);
        if (task.status() == DaemonTaskStatus.COMPLETED || task.status() == DaemonTaskStatus.FAILED) {
            return;
        }
        repository.saveTask(task.withStatus(DaemonTaskStatus.RUNNING));
    }

    void waitLocalDirectory(String taskId, DaemonModels.WaitingLocalDirectoryRequest request) {
        TaskRecord task = task(taskId);
        if (task.status() != DaemonTaskStatus.DISPATCHED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "task is not dispatched");
        }
        repository.saveTask(task.withWaitingLocalDirectory(request == null ? null : request.reason()));
    }

    void progress(String taskId, DaemonModels.ProgressRequest request) {
        TaskRecord task = task(taskId);
        if (task.status() == DaemonTaskStatus.COMPLETED || task.status() == DaemonTaskStatus.FAILED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "task is terminal");
        }
        repository.saveTask(task.withProgress(request.summary(), request.step(), request.total()));
    }

    void complete(String taskId, DaemonModels.CompleteRequest request) {
        TaskRecord task = task(taskId);
        if (task.status() == DaemonTaskStatus.COMPLETED) {
            return;
        }
        if (task.status() == DaemonTaskStatus.FAILED) {
            return;
        }
        repository.saveTask(task.withComplete(request.output(), request.branchName(), request.sessionId(), request.workDir()));
    }

    void fail(String taskId, DaemonModels.FailRequest request) {
        TaskRecord task = task(taskId);
        if (task.status() == DaemonTaskStatus.COMPLETED || task.status() == DaemonTaskStatus.FAILED) {
            return;
        }
        repository.saveTask(task.withFail(request.error(), request.sessionId(), request.workDir(), request.failureReason()));
    }

    void pinSession(String taskId, DaemonModels.SessionRequest request) {
        TaskRecord task = task(taskId);
        repository.saveTask(task.withSession(request.sessionId(), request.workDir()));
    }

    void usage(String taskId, DaemonModels.UsageRequest request) {
        task(taskId);
        repository.appendUsage(taskId, request == null ? List.of() : DaemonModels.copyList(request.usage()));
    }

    void messages(String taskId, DaemonModels.MessagesRequest request) {
        task(taskId);
        repository.appendMessages(taskId, request == null ? List.of() : DaemonModels.copyList(request.messages()));
    }

    List<DaemonModels.TaskMessageData> listMessages(String taskId) {
        task(taskId);
        return repository.messages(taskId);
    }

    void recoverOrphans(String runtimeId) {
        runtime(runtimeId);
    }

    DaemonModels.IssueGcStatus issueGc(String issueId) {
        return new DaemonModels.IssueGcStatus("in_progress", Instant.now());
    }

    DaemonModels.ChatSessionGcStatus chatSessionGc(String sessionId) {
        return new DaemonModels.ChatSessionGcStatus("active", Instant.now());
    }

    DaemonModels.AutopilotRunGcStatus autopilotRunGc(String runId) {
        return new DaemonModels.AutopilotRunGcStatus("completed", Instant.now());
    }

    DaemonModels.TaskGcStatus taskGc(String taskId) {
        TaskRecord task = task(taskId);
        return new DaemonModels.TaskGcStatus(task.status().wireName(), task.completedAt());
    }

    private RuntimeRecord runtime(String runtimeId) {
        return repository.findRuntime(runtimeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "runtime not found"));
    }

    private TaskRecord task(String taskId) {
        return repository.findTask(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "task not found"));
    }

    private DaemonModels.RuntimeView runtimeView(RuntimeRecord runtime) {
        return new DaemonModels.RuntimeView(runtime.id(), runtime.name(), runtime.provider(), runtime.status());
    }

    private DaemonModels.TaskView taskView(TaskRecord task) {
        return new DaemonModels.TaskView(task.id(), task.agentId(), task.runtimeId(), task.issueId(), task.workspaceId(),
                task.workspaceContext(), task.agent(), task.repos(), task.projectId(), task.projectTitle(),
                task.projectResources(), task.priorSessionId(), task.priorWorkDir(), task.triggerCommentId(),
                task.triggerThreadId(), task.triggerCommentContent(), task.triggerAuthorType(), task.triggerAuthorName(),
                task.newCommentCount(), task.newCommentsSince(), task.chatSessionId(), task.chatMessage(),
                task.chatMessageAttachments(), task.autopilotRunId(), task.autopilotId(), task.autopilotTitle(),
                task.autopilotDescription(), task.autopilotSource(), task.autopilotTriggerPayload(),
                task.quickCreatePrompt(), task.squadId(), task.squadName(), task.parentIssueId(),
                task.parentIssueIdentifier(), task.requestingUserName(), task.requestingUserProfileDescription(),
                task.authToken());
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private static String defaultString(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
