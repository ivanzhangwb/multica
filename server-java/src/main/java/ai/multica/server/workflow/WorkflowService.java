package ai.multica.server.workflow;

import ai.multica.server.workflow.WorkflowModels.AttachmentEntity;
import ai.multica.server.workflow.WorkflowModels.AttachmentResponse;
import ai.multica.server.workflow.WorkflowModels.CommentEntity;
import ai.multica.server.workflow.WorkflowModels.CommentResponse;
import ai.multica.server.workflow.WorkflowModels.CreateCommentRequest;
import ai.multica.server.workflow.WorkflowModels.CreateIssueRequest;
import ai.multica.server.workflow.WorkflowModels.CreateLabelRequest;
import ai.multica.server.workflow.WorkflowModels.CreateProjectRequest;
import ai.multica.server.workflow.WorkflowModels.IssueEntity;
import ai.multica.server.workflow.WorkflowModels.IssueResponse;
import ai.multica.server.workflow.WorkflowModels.LabelEntity;
import ai.multica.server.workflow.WorkflowModels.LabelResponse;
import ai.multica.server.workflow.WorkflowModels.MetadataValueRequest;
import ai.multica.server.workflow.WorkflowModels.ProjectEntity;
import ai.multica.server.workflow.WorkflowModels.ProjectResponse;
import ai.multica.server.workflow.WorkflowModels.SubscribeRequest;
import ai.multica.server.workflow.WorkflowModels.SubscriberEntity;
import ai.multica.server.workflow.WorkflowModels.SubscriberResponse;
import ai.multica.server.workflow.WorkflowModels.UpdateCommentRequest;
import ai.multica.server.workflow.WorkflowModels.UpdateIssueRequest;
import ai.multica.server.workflow.WorkflowModels.UpdateLabelRequest;
import ai.multica.server.workflow.WorkflowModels.UpdateProjectRequest;
import ai.multica.server.workflow.WorkflowModels;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WorkflowService {
    private static final Pattern HEX_COLOR = Pattern.compile("^#?[0-9a-fA-F]{6}$");
    private static final Pattern METADATA_KEY = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.-]{0,63}$");
    private static final int MAX_METADATA_KEYS = 50;
    private final WorkflowRepository repository;

    public WorkflowService(WorkflowRepository repository) {
        this.repository = repository;
    }

    public List<IssueResponse> listIssues(String workspaceId) {
        return repository.listIssues(workspaceId).stream()
            .map(issue -> WorkflowModels.toResponse(issue, issueAttachments(workspaceId, issue.id), labelsForIssue(workspaceId, issue)))
            .toList();
    }

    public IssueResponse getIssue(String workspaceId, String issueId) {
        IssueEntity issue = issue(workspaceId, issueId);
        return WorkflowModels.toResponse(issue, issueAttachments(workspaceId, issue.id), labelsForIssue(workspaceId, issue));
    }

    public IssueResponse createIssue(String workspaceId, String actorType, String actorId, CreateIssueRequest request) {
        if (!StringUtils.hasText(request.title())) {
            throw new ValidationException("title is required");
        }
        IssueEntity issue = new IssueEntity();
        issue.workspaceId = workspaceId;
        issue.number = repository.nextIssueNumber(workspaceId);
        issue.identifier = "MUL-" + issue.number;
        issue.title = request.title().trim();
        issue.description = request.description();
        issue.status = defaulted(request.status(), "todo");
        issue.priority = defaulted(request.priority(), "none");
        issue.assigneeType = blankToNull(request.assigneeType());
        issue.assigneeId = blankToNull(request.assigneeId());
        issue.creatorType = actorType;
        issue.creatorId = actorId;
        issue.parentIssueId = blankToNull(request.parentIssueId());
        issue.projectId = blankToNull(request.projectId());
        issue.startDate = blankToNull(request.startDate());
        issue.dueDate = blankToNull(request.dueDate());
        issue.attachmentIds = normalizedIds(request.attachmentIds(), "attachment_ids");
        repository.saveIssue(issue);
        linkAttachmentsToIssue(workspaceId, issue.id, issue.attachmentIds);
        return WorkflowModels.toResponse(issue, issueAttachments(workspaceId, issue.id), List.of());
    }

    public IssueResponse updateIssue(String workspaceId, String issueId, UpdateIssueRequest request) {
        IssueEntity issue = issue(workspaceId, issueId);
        if (request.title() != null) {
            if (!StringUtils.hasText(request.title())) {
                throw new ValidationException("title is required");
            }
            issue.title = request.title().trim();
        }
        if (request.description() != null) issue.description = request.description();
        if (request.status() != null) issue.status = request.status();
        if (request.priority() != null) issue.priority = request.priority();
        if (request.assigneeType() != null) issue.assigneeType = blankToNull(request.assigneeType());
        if (request.assigneeId() != null) issue.assigneeId = blankToNull(request.assigneeId());
        if (request.parentIssueId() != null) issue.parentIssueId = blankToNull(request.parentIssueId());
        if (request.projectId() != null) issue.projectId = blankToNull(request.projectId());
        if (request.startDate() != null) issue.startDate = blankToNull(request.startDate());
        if (request.dueDate() != null) issue.dueDate = blankToNull(request.dueDate());
        if (request.attachmentIds() != null) {
            issue.attachmentIds = normalizedIds(request.attachmentIds(), "attachment_ids");
            linkAttachmentsToIssue(workspaceId, issue.id, issue.attachmentIds);
        }
        issue.updatedAt = WorkflowModels.now();
        repository.saveIssue(issue);
        return WorkflowModels.toResponse(issue, issueAttachments(workspaceId, issue.id), null);
    }

    public void deleteIssue(String workspaceId, String issueId) {
        issue(workspaceId, issueId);
        repository.deleteIssue(workspaceId, issueId);
    }

    public Map<String, Object> metadata(String workspaceId, String issueId) {
        return new LinkedHashMap<>(issue(workspaceId, issueId).metadata);
    }

    public Map<String, Object> setMetadata(String workspaceId, String issueId, String key, MetadataValueRequest request) {
        validateMetadataKey(key);
        if (request == null || request.value() == null || request.value().isNull()) {
            throw new ValidationException("value cannot be null (use DELETE to remove a key)");
        }
        Object value = metadataPrimitive(request.value());
        IssueEntity issue = issue(workspaceId, issueId);
        if (!issue.metadata.containsKey(key) && issue.metadata.size() >= MAX_METADATA_KEYS) {
            throw new ValidationException("metadata cannot exceed 50 keys");
        }
        issue.metadata.put(key, value);
        issue.updatedAt = WorkflowModels.now();
        repository.saveIssue(issue);
        return new LinkedHashMap<>(issue.metadata);
    }

    public Map<String, Object> deleteMetadata(String workspaceId, String issueId, String key) {
        validateMetadataKey(key);
        IssueEntity issue = issue(workspaceId, issueId);
        issue.metadata.remove(key);
        issue.updatedAt = WorkflowModels.now();
        repository.saveIssue(issue);
        return new LinkedHashMap<>(issue.metadata);
    }

    public List<ProjectResponse> listProjects(String workspaceId) {
        return repository.listProjects(workspaceId).stream().map(p -> projectResponse(p)).toList();
    }

    public ProjectResponse getProject(String workspaceId, String projectId) {
        return projectResponse(project(workspaceId, projectId));
    }

    public ProjectResponse createProject(String workspaceId, CreateProjectRequest request) {
        if (!StringUtils.hasText(request.title())) throw new ValidationException("title is required");
        ProjectEntity project = new ProjectEntity();
        project.workspaceId = workspaceId;
        project.title = request.title().trim();
        project.description = request.description();
        project.icon = request.icon();
        project.status = defaulted(request.status(), "active");
        project.priority = defaulted(request.priority(), "none");
        project.leadType = blankToNull(request.leadType());
        project.leadId = blankToNull(request.leadId());
        repository.saveProject(project);
        return projectResponse(project);
    }

    public ProjectResponse updateProject(String workspaceId, String projectId, UpdateProjectRequest request) {
        ProjectEntity project = project(workspaceId, projectId);
        if (request.title() != null) {
            if (!StringUtils.hasText(request.title())) throw new ValidationException("title is required");
            project.title = request.title().trim();
        }
        if (request.description() != null) project.description = request.description();
        if (request.icon() != null) project.icon = request.icon();
        if (request.status() != null) project.status = request.status();
        if (request.priority() != null) project.priority = request.priority();
        if (request.leadType() != null) project.leadType = blankToNull(request.leadType());
        if (request.leadId() != null) project.leadId = blankToNull(request.leadId());
        project.updatedAt = WorkflowModels.now();
        repository.saveProject(project);
        return projectResponse(project);
    }

    public void deleteProject(String workspaceId, String projectId) {
        project(workspaceId, projectId);
        repository.deleteProject(workspaceId, projectId);
    }

    public List<LabelResponse> listLabels(String workspaceId) {
        return repository.listLabels(workspaceId).stream().map(this::labelResponse).toList();
    }

    public LabelResponse createLabel(String workspaceId, CreateLabelRequest request) {
        LabelEntity label = new LabelEntity();
        label.workspaceId = workspaceId;
        label.name = validateLabelName(request.name());
        label.color = normalizeColor(request.color());
        repository.saveLabel(label);
        return labelResponse(label);
    }

    public LabelResponse updateLabel(String workspaceId, String labelId, UpdateLabelRequest request) {
        LabelEntity label = label(workspaceId, labelId);
        if (request.name() != null) label.name = validateLabelName(request.name());
        if (request.color() != null) label.color = normalizeColor(request.color());
        label.updatedAt = WorkflowModels.now();
        repository.saveLabel(label);
        return labelResponse(label);
    }

    public void deleteLabel(String workspaceId, String labelId) {
        label(workspaceId, labelId);
        repository.deleteLabel(workspaceId, labelId);
    }

    public void addIssueLabel(String workspaceId, String issueId, String labelId) {
        IssueEntity issue = issue(workspaceId, issueId);
        label(workspaceId, labelId);
        if (!issue.labelIds.contains(labelId)) {
            issue.labelIds.add(labelId);
            issue.updatedAt = WorkflowModels.now();
            repository.saveIssue(issue);
        }
    }

    public void removeIssueLabel(String workspaceId, String issueId, String labelId) {
        IssueEntity issue = issue(workspaceId, issueId);
        issue.labelIds.remove(labelId);
        issue.updatedAt = WorkflowModels.now();
        repository.saveIssue(issue);
    }

    public List<CommentResponse> listComments(String workspaceId, String issueId) {
        issue(workspaceId, issueId);
        return repository.listComments(issueId).stream()
            .map(comment -> commentResponse(workspaceId, comment))
            .toList();
    }

    public CommentResponse createComment(String workspaceId, String issueId, String actorType, String actorId, CreateCommentRequest request) {
        issue(workspaceId, issueId);
        if (!StringUtils.hasText(request.content())) throw new ValidationException("content is required");
        CommentEntity comment = new CommentEntity();
        comment.issueId = issueId;
        comment.authorType = actorType;
        comment.authorId = actorId;
        comment.content = request.content();
        comment.type = defaulted(request.type(), "comment");
        comment.parentId = blankToNull(request.parentId());
        comment.attachmentIds = normalizedIds(request.attachmentIds(), "attachment_ids");
        repository.saveComment(comment);
        linkAttachmentsToComment(workspaceId, issueId, comment.id, comment.attachmentIds);
        return commentResponse(workspaceId, comment);
    }

    public CommentResponse updateComment(String workspaceId, String commentId, UpdateCommentRequest request) {
        CommentEntity comment = repository.findCommentById(commentId)
            .orElseThrow(() -> new NotFoundException("comment not found"));
        issue(workspaceId, comment.issueId);
        if (request.content() != null) {
            if (!StringUtils.hasText(request.content())) throw new ValidationException("content is required");
            comment.content = request.content();
        }
        if (request.attachmentIds() != null) {
            comment.attachmentIds = normalizedIds(request.attachmentIds(), "attachment_ids");
            linkAttachmentsToComment(workspaceId, comment.issueId, comment.id, comment.attachmentIds);
        }
        comment.updatedAt = WorkflowModels.now();
        repository.saveComment(comment);
        return commentResponse(workspaceId, comment);
    }

    public void deleteComment(String workspaceId, String commentId) {
        CommentEntity comment = repository.findCommentById(commentId)
            .orElseThrow(() -> new NotFoundException("comment not found"));
        issue(workspaceId, comment.issueId);
        repository.deleteComment(comment.issueId, commentId);
    }

    public List<SubscriberResponse> listSubscribers(String workspaceId, String issueId) {
        issue(workspaceId, issueId);
        return repository.listSubscribers(issueId).stream()
            .map(s -> new SubscriberResponse(s.issueId, s.userType, s.userId, s.reason, s.createdAt))
            .toList();
    }

    public Map<String, Boolean> subscribe(String workspaceId, String issueId, String actorType, String actorId, SubscribeRequest request) {
        issue(workspaceId, issueId);
        SubscriberEntity subscriber = new SubscriberEntity();
        subscriber.issueId = issueId;
        subscriber.userType = defaulted(request == null ? null : request.userType(), actorType);
        subscriber.userId = defaulted(request == null ? null : request.userId(), actorId);
        repository.saveSubscriber(subscriber);
        return Map.of("subscribed", true);
    }

    public Map<String, Boolean> unsubscribe(String workspaceId, String issueId, String actorType, String actorId, SubscribeRequest request) {
        issue(workspaceId, issueId);
        String userType = defaulted(request == null ? null : request.userType(), actorType);
        String userId = defaulted(request == null ? null : request.userId(), actorId);
        repository.deleteSubscriber(issueId, userType, userId);
        return Map.of("subscribed", false);
    }

    public AttachmentResponse getAttachment(String workspaceId, String attachmentId) {
        return attachmentResponse(attachment(workspaceId, attachmentId));
    }

    public void deleteAttachment(String workspaceId, String attachmentId) {
        attachment(workspaceId, attachmentId);
        repository.deleteAttachment(workspaceId, attachmentId);
    }

    private IssueEntity issue(String workspaceId, String issueId) {
        return repository.findIssue(workspaceId, issueId)
            .orElseThrow(() -> new NotFoundException("issue not found"));
    }

    private ProjectEntity project(String workspaceId, String projectId) {
        return repository.findProject(workspaceId, projectId)
            .orElseThrow(() -> new NotFoundException("project not found"));
    }

    private LabelEntity label(String workspaceId, String labelId) {
        return repository.findLabel(workspaceId, labelId)
            .orElseThrow(() -> new NotFoundException("label not found"));
    }

    private AttachmentEntity attachment(String workspaceId, String attachmentId) {
        return repository.findAttachment(workspaceId, attachmentId)
            .orElseThrow(() -> new NotFoundException("attachment not found"));
    }

    private List<AttachmentResponse> issueAttachments(String workspaceId, String issueId) {
        return repository.listIssueAttachments(workspaceId, issueId).stream().map(this::attachmentResponse).toList();
    }

    private List<LabelResponse> labelsForIssue(String workspaceId, IssueEntity issue) {
        return issue.labelIds.stream()
            .map(id -> repository.findLabel(workspaceId, id))
            .flatMap(Optional::stream)
            .map(this::labelResponse)
            .toList();
    }

    private void linkAttachmentsToIssue(String workspaceId, String issueId, List<String> ids) {
        for (String id : ids) {
            AttachmentEntity attachment = repository.findAttachment(workspaceId, id).orElseGet(() -> {
                AttachmentEntity created = new AttachmentEntity();
                created.id = id;
                created.workspaceId = workspaceId;
                created.uploaderId = "00000000-0000-0000-0000-000000000000";
                created.url = "/api/attachments/" + id + "/download";
                return created;
            });
            attachment.issueId = issueId;
            repository.saveAttachment(attachment);
        }
    }

    private void linkAttachmentsToComment(String workspaceId, String issueId, String commentId, List<String> ids) {
        for (String id : ids) {
            AttachmentEntity attachment = repository.findAttachment(workspaceId, id).orElseGet(() -> {
                AttachmentEntity created = new AttachmentEntity();
                created.id = id;
                created.workspaceId = workspaceId;
                created.uploaderId = "00000000-0000-0000-0000-000000000000";
                created.url = "/api/attachments/" + id + "/download";
                return created;
            });
            attachment.issueId = issueId;
            attachment.commentId = commentId;
            repository.saveAttachment(attachment);
        }
    }

    private ProjectResponse projectResponse(ProjectEntity project) {
        long issueCount = repository.listIssues(project.workspaceId).stream()
            .filter(i -> project.id.equals(i.projectId))
            .count();
        long doneCount = repository.listIssues(project.workspaceId).stream()
            .filter(i -> project.id.equals(i.projectId))
            .filter(i -> "done".equals(i.status))
            .count();
        return new ProjectResponse(
            project.id,
            project.workspaceId,
            project.title,
            project.description,
            project.icon,
            project.status,
            project.priority,
            project.leadType,
            project.leadId,
            project.createdAt,
            project.updatedAt,
            issueCount,
            doneCount,
            0
        );
    }

    private LabelResponse labelResponse(LabelEntity label) {
        return new LabelResponse(label.id, label.workspaceId, label.name, label.color, label.createdAt, label.updatedAt);
    }

    private CommentResponse commentResponse(String workspaceId, CommentEntity comment) {
        List<AttachmentResponse> attachments = repository.listCommentAttachments(comment.issueId, comment.id).stream()
            .map(this::attachmentResponse)
            .toList();
        return new CommentResponse(
            comment.id,
            comment.issueId,
            comment.authorType,
            comment.authorId,
            comment.content,
            comment.type,
            comment.parentId,
            comment.createdAt,
            comment.updatedAt,
            comment.resolvedAt,
            comment.resolvedByType,
            comment.resolvedById,
            List.of(),
            attachments
        );
    }

    private AttachmentResponse attachmentResponse(AttachmentEntity attachment) {
        return new AttachmentResponse(
            attachment.id,
            attachment.workspaceId,
            attachment.issueId,
            attachment.commentId,
            attachment.chatSessionId,
            attachment.chatMessageId,
            attachment.uploaderType,
            attachment.uploaderId,
            attachment.filename,
            attachment.url,
            "/api/attachments/" + attachment.id + "/download",
            attachment.contentType,
            attachment.sizeBytes,
            attachment.createdAt
        );
    }

    private List<String> normalizedIds(List<String> ids, String field) {
        if (ids == null) return List.of();
        return ids.stream().map(id -> {
            try {
                return UUID.fromString(id).toString();
            } catch (IllegalArgumentException ex) {
                throw new ValidationException(field + " must contain UUID strings");
            }
        }).toList();
    }

    private String normalizeColor(String raw) {
        if (!StringUtils.hasText(raw) || !HEX_COLOR.matcher(raw.trim()).matches()) {
            throw new ValidationException("color must be a 6-digit hex value like #3b82f6");
        }
        String color = raw.trim();
        if (!color.startsWith("#")) color = "#" + color;
        return color.toLowerCase(Locale.ROOT);
    }

    private String validateLabelName(String raw) {
        if (!StringUtils.hasText(raw)) throw new ValidationException("name is required");
        String name = raw.trim();
        if (name.length() > 32) throw new ValidationException("name must be 32 characters or fewer");
        return name;
    }

    private void validateMetadataKey(String key) {
        if (!StringUtils.hasText(key)) throw new ValidationException("key is required");
        if (!METADATA_KEY.matcher(key).matches()) {
            throw new ValidationException("key must match ^[a-zA-Z_][a-zA-Z0-9_.-]{0,63}$");
        }
    }

    private Object metadataPrimitive(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.numberValue();
        throw new ValidationException("value must be a primitive: string, number, or bool");
    }

    private String defaulted(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
