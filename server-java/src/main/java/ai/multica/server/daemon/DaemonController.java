package ai.multica.server.daemon;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/daemon")
class DaemonController {
    private final DaemonService service;

    DaemonController(DaemonService service) {
        this.service = service;
    }

    @PostMapping("/register")
    DaemonModels.RegisterResponse register(@RequestBody DaemonModels.RegisterRequest request) {
        return service.register(request);
    }

    @PostMapping("/deregister")
    ResponseEntity<Void> deregister(@RequestBody Map<String, List<String>> request) {
        service.deregister(request.get("runtime_ids"));
        return ResponseEntity.ok().build();
    }

    @PostMapping("/heartbeat")
    DaemonModels.HeartbeatResponse heartbeat(@RequestBody DaemonModels.HeartbeatRequest request) {
        return service.heartbeat(request);
    }

    @GetMapping("/workspaces/{workspaceId}/repos")
    DaemonModels.WorkspaceReposResponse workspaceRepos(@PathVariable String workspaceId) {
        return service.workspaceRepos(workspaceId);
    }

    @PostMapping("/runtimes/{runtimeId}/tasks/claim")
    DaemonModels.ClaimResponse claim(@PathVariable String runtimeId) {
        return service.claim(runtimeId);
    }

    @GetMapping("/tasks/{taskId}/status")
    DaemonModels.StatusResponse taskStatus(@PathVariable String taskId) {
        return service.taskStatus(taskId);
    }

    @PostMapping("/tasks/{taskId}/start")
    ResponseEntity<Void> startTask(@PathVariable String taskId) {
        service.startTask(taskId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tasks/{taskId}/wait-local-directory")
    ResponseEntity<Void> waitLocalDirectory(
            @PathVariable String taskId,
            @RequestBody DaemonModels.WaitingLocalDirectoryRequest request) {
        service.waitLocalDirectory(taskId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tasks/{taskId}/progress")
    ResponseEntity<Void> progress(@PathVariable String taskId, @RequestBody DaemonModels.ProgressRequest request) {
        service.progress(taskId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tasks/{taskId}/complete")
    ResponseEntity<Void> complete(@PathVariable String taskId, @RequestBody DaemonModels.CompleteRequest request) {
        service.complete(taskId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tasks/{taskId}/fail")
    ResponseEntity<Void> fail(@PathVariable String taskId, @RequestBody DaemonModels.FailRequest request) {
        service.fail(taskId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tasks/{taskId}/session")
    ResponseEntity<Void> session(@PathVariable String taskId, @RequestBody DaemonModels.SessionRequest request) {
        service.pinSession(taskId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tasks/{taskId}/usage")
    ResponseEntity<Void> usage(@PathVariable String taskId, @RequestBody DaemonModels.UsageRequest request) {
        service.usage(taskId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tasks/{taskId}/messages")
    ResponseEntity<Void> messages(@PathVariable String taskId, @RequestBody DaemonModels.MessagesRequest request) {
        service.messages(taskId, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/tasks/{taskId}/messages")
    List<DaemonModels.TaskMessageData> listMessages(@PathVariable String taskId) {
        return service.listMessages(taskId);
    }

    @PostMapping("/runtimes/{runtimeId}/recover-orphans")
    ResponseEntity<Void> recoverOrphans(@PathVariable String runtimeId) {
        service.recoverOrphans(runtimeId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/issues/{issueId}/gc-check")
    DaemonModels.IssueGcStatus issueGc(@PathVariable String issueId) {
        return service.issueGc(issueId);
    }

    @GetMapping("/chat-sessions/{sessionId}/gc-check")
    DaemonModels.ChatSessionGcStatus chatSessionGc(@PathVariable String sessionId) {
        return service.chatSessionGc(sessionId);
    }

    @GetMapping("/autopilot-runs/{runId}/gc-check")
    DaemonModels.AutopilotRunGcStatus autopilotRunGc(@PathVariable String runId) {
        return service.autopilotRunGc(runId);
    }

    @GetMapping("/tasks/{taskId}/gc-check")
    DaemonModels.TaskGcStatus taskGc(@PathVariable String taskId) {
        return service.taskGc(taskId);
    }
}
