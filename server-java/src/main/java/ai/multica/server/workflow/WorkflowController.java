package ai.multica.server.workflow;

import ai.multica.server.workflow.WorkflowModels.CreateCommentRequest;
import ai.multica.server.workflow.WorkflowModels.CreateIssueRequest;
import ai.multica.server.workflow.WorkflowModels.CreateLabelRequest;
import ai.multica.server.workflow.WorkflowModels.CreateProjectRequest;
import ai.multica.server.workflow.WorkflowModels.MetadataValueRequest;
import ai.multica.server.workflow.WorkflowModels.SubscribeRequest;
import ai.multica.server.workflow.WorkflowModels.UpdateCommentRequest;
import ai.multica.server.workflow.WorkflowModels.UpdateIssueRequest;
import ai.multica.server.workflow.WorkflowModels.UpdateLabelRequest;
import ai.multica.server.workflow.WorkflowModels.UpdateProjectRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkflowController {
    private static final String DEFAULT_ACTOR_ID = "00000000-0000-0000-0000-000000000000";
    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @GetMapping("/api/issues")
    Map<String, Object> listIssues(@RequestParam(name = "workspace_id", required = false) String workspaceId) {
        var issues = service.listIssues(workspace(workspaceId));
        return Map.of("issues", issues, "total", issues.size());
    }

    @PostMapping("/api/issues")
    ResponseEntity<?> createIssue(
        @RequestParam(name = "workspace_id", required = false) String workspaceId,
        @RequestHeader(name = "X-Agent-ID", required = false) String agentId,
        @RequestHeader(name = "X-User-ID", required = false) String userId,
        @Valid @RequestBody CreateIssueRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createIssue(workspace(workspaceId), actorType(agentId), actorId(agentId, userId), request));
    }

    @GetMapping("/api/issues/{id}")
    Object getIssue(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        return service.getIssue(workspace(workspaceId), id);
    }

    @PutMapping("/api/issues/{id}")
    Object updateIssue(
        @PathVariable String id,
        @RequestParam(name = "workspace_id", required = false) String workspaceId,
        @RequestBody UpdateIssueRequest request
    ) {
        return service.updateIssue(workspace(workspaceId), id, request);
    }

    @PatchMapping("/api/issues/{id}")
    Object patchIssue(
        @PathVariable String id,
        @RequestParam(name = "workspace_id", required = false) String workspaceId,
        @RequestBody UpdateIssueRequest request
    ) {
        return service.updateIssue(workspace(workspaceId), id, request);
    }

    @DeleteMapping("/api/issues/{id}")
    ResponseEntity<Void> deleteIssue(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        service.deleteIssue(workspace(workspaceId), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/issues/{id}/metadata")
    Map<String, Object> listMetadata(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        return Map.of("metadata", service.metadata(workspace(workspaceId), id));
    }

    @PutMapping("/api/issues/{id}/metadata/{key}")
    Map<String, Object> setMetadata(
        @PathVariable String id,
        @PathVariable String key,
        @RequestParam(name = "workspace_id", required = false) String workspaceId,
        @RequestBody MetadataValueRequest request
    ) {
        return Map.of("metadata", service.setMetadata(workspace(workspaceId), id, key, request));
    }

    @DeleteMapping("/api/issues/{id}/metadata/{key}")
    Map<String, Object> deleteMetadata(
        @PathVariable String id,
        @PathVariable String key,
        @RequestParam(name = "workspace_id", required = false) String workspaceId
    ) {
        return Map.of("metadata", service.deleteMetadata(workspace(workspaceId), id, key));
    }

    @GetMapping("/api/projects")
    Map<String, Object> listProjects(@RequestParam(name = "workspace_id", required = false) String workspaceId) {
        var projects = service.listProjects(workspace(workspaceId));
        return Map.of("projects", projects, "total", projects.size());
    }

    @PostMapping("/api/projects")
    ResponseEntity<?> createProject(
        @RequestParam(name = "workspace_id", required = false) String workspaceId,
        @Valid @RequestBody CreateProjectRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createProject(workspace(workspaceId), request));
    }

    @GetMapping("/api/projects/{id}")
    Object getProject(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        return service.getProject(workspace(workspaceId), id);
    }

    @PutMapping("/api/projects/{id}")
    Object updateProject(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId, @RequestBody UpdateProjectRequest request) {
        return service.updateProject(workspace(workspaceId), id, request);
    }

    @DeleteMapping("/api/projects/{id}")
    ResponseEntity<Void> deleteProject(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        service.deleteProject(workspace(workspaceId), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/labels")
    Map<String, Object> listLabels(@RequestParam(name = "workspace_id", required = false) String workspaceId) {
        var labels = service.listLabels(workspace(workspaceId));
        return Map.of("labels", labels, "total", labels.size());
    }

    @PostMapping("/api/labels")
    ResponseEntity<?> createLabel(@RequestParam(name = "workspace_id", required = false) String workspaceId, @Valid @RequestBody CreateLabelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createLabel(workspace(workspaceId), request));
    }

    @PutMapping("/api/labels/{id}")
    Object updateLabel(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId, @RequestBody UpdateLabelRequest request) {
        return service.updateLabel(workspace(workspaceId), id, request);
    }

    @DeleteMapping("/api/labels/{id}")
    ResponseEntity<Void> deleteLabel(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        service.deleteLabel(workspace(workspaceId), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/issues/{id}/labels")
    Map<String, Object> listIssueLabels(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        return Map.of("labels", service.getIssue(workspace(workspaceId), id).labels());
    }

    @PostMapping("/api/issues/{id}/labels/{labelId}")
    Map<String, Boolean> addIssueLabel(@PathVariable String id, @PathVariable String labelId, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        service.addIssueLabel(workspace(workspaceId), id, labelId);
        return Map.of("added", true);
    }

    @DeleteMapping("/api/issues/{id}/labels/{labelId}")
    Map<String, Boolean> removeIssueLabel(@PathVariable String id, @PathVariable String labelId, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        service.removeIssueLabel(workspace(workspaceId), id, labelId);
        return Map.of("removed", true);
    }

    @GetMapping("/api/issues/{id}/comments")
    Object listComments(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        return service.listComments(workspace(workspaceId), id);
    }

    @PostMapping("/api/issues/{id}/comments")
    ResponseEntity<?> createComment(
        @PathVariable String id,
        @RequestParam(name = "workspace_id", required = false) String workspaceId,
        @RequestHeader(name = "X-Agent-ID", required = false) String agentId,
        @RequestHeader(name = "X-User-ID", required = false) String userId,
        @Valid @RequestBody CreateCommentRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(service.createComment(workspace(workspaceId), id, actorType(agentId), actorId(agentId, userId), request));
    }

    @PutMapping("/api/comments/{commentId}")
    Object updateComment(@PathVariable String commentId, @RequestParam(name = "workspace_id", required = false) String workspaceId, @RequestBody UpdateCommentRequest request) {
        return service.updateComment(workspace(workspaceId), commentId, request);
    }

    @DeleteMapping("/api/comments/{commentId}")
    ResponseEntity<Void> deleteComment(@PathVariable String commentId, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        service.deleteComment(workspace(workspaceId), commentId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/issues/{id}/subscribers")
    Object listSubscribers(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        return service.listSubscribers(workspace(workspaceId), id);
    }

    @PostMapping("/api/issues/{id}/subscribers")
    Object subscribe(
        @PathVariable String id,
        @RequestParam(name = "workspace_id", required = false) String workspaceId,
        @RequestHeader(name = "X-Agent-ID", required = false) String agentId,
        @RequestHeader(name = "X-User-ID", required = false) String userId,
        @RequestBody(required = false) SubscribeRequest request
    ) {
        return service.subscribe(workspace(workspaceId), id, actorType(agentId), actorId(agentId, userId), request);
    }

    @DeleteMapping("/api/issues/{id}/subscribers")
    Object unsubscribe(
        @PathVariable String id,
        @RequestParam(name = "workspace_id", required = false) String workspaceId,
        @RequestHeader(name = "X-Agent-ID", required = false) String agentId,
        @RequestHeader(name = "X-User-ID", required = false) String userId,
        @RequestBody(required = false) SubscribeRequest request
    ) {
        return service.unsubscribe(workspace(workspaceId), id, actorType(agentId), actorId(agentId, userId), request);
    }

    @GetMapping("/api/attachments/{id}")
    Object getAttachment(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        return service.getAttachment(workspace(workspaceId), id);
    }

    @DeleteMapping("/api/attachments/{id}")
    ResponseEntity<Void> deleteAttachment(@PathVariable String id, @RequestParam(name = "workspace_id", required = false) String workspaceId) {
        service.deleteAttachment(workspace(workspaceId), id);
        return ResponseEntity.noContent().build();
    }

    private String workspace(String workspaceId) {
        return workspaceId == null || workspaceId.isBlank() ? "00000000-0000-0000-0000-000000000001" : workspaceId;
    }

    private String actorType(String agentId) {
        return agentId == null || agentId.isBlank() ? "member" : "agent";
    }

    private String actorId(String agentId, String userId) {
        if (agentId != null && !agentId.isBlank()) return agentId;
        if (userId != null && !userId.isBlank()) return userId;
        return DEFAULT_ACTOR_ID;
    }
}
