package ai.multica.server.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkflowModels {
    private WorkflowModels() {}

    public static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public record AttachmentResponse(
        String id,
        @JsonProperty("workspace_id") String workspaceId,
        @JsonProperty("issue_id") String issueId,
        @JsonProperty("comment_id") String commentId,
        @JsonProperty("chat_session_id") String chatSessionId,
        @JsonProperty("chat_message_id") String chatMessageId,
        @JsonProperty("uploader_type") String uploaderType,
        @JsonProperty("uploader_id") String uploaderId,
        String filename,
        String url,
        @JsonProperty("download_url") String downloadUrl,
        @JsonProperty("content_type") String contentType,
        @JsonProperty("size_bytes") long sizeBytes,
        @JsonProperty("created_at") String createdAt
    ) {}

    public record LabelResponse(
        String id,
        @JsonProperty("workspace_id") String workspaceId,
        String name,
        String color,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
    ) {}

    public record IssueResponse(
        String id,
        @JsonProperty("workspace_id") String workspaceId,
        int number,
        String identifier,
        String title,
        String description,
        String status,
        String priority,
        @JsonProperty("assignee_type") String assigneeType,
        @JsonProperty("assignee_id") String assigneeId,
        @JsonProperty("creator_type") String creatorType,
        @JsonProperty("creator_id") String creatorId,
        @JsonProperty("parent_issue_id") String parentIssueId,
        @JsonProperty("project_id") String projectId,
        double position,
        @JsonProperty("start_date") String startDate,
        @JsonProperty("due_date") String dueDate,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        Map<String, Object> metadata,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AttachmentResponse> attachments,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<LabelResponse> labels
    ) {}

    public record ProjectResponse(
        String id,
        @JsonProperty("workspace_id") String workspaceId,
        String title,
        String description,
        String icon,
        String status,
        String priority,
        @JsonProperty("lead_type") String leadType,
        @JsonProperty("lead_id") String leadId,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("issue_count") long issueCount,
        @JsonProperty("done_count") long doneCount,
        @JsonProperty("resource_count") long resourceCount
    ) {}

    public record CommentResponse(
        String id,
        @JsonProperty("issue_id") String issueId,
        @JsonProperty("author_type") String authorType,
        @JsonProperty("author_id") String authorId,
        String content,
        String type,
        @JsonProperty("parent_id") String parentId,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("resolved_at") String resolvedAt,
        @JsonProperty("resolved_by_type") String resolvedByType,
        @JsonProperty("resolved_by_id") String resolvedById,
        List<Object> reactions,
        List<AttachmentResponse> attachments
    ) {}

    public record SubscriberResponse(
        @JsonProperty("issue_id") String issueId,
        @JsonProperty("user_type") String userType,
        @JsonProperty("user_id") String userId,
        String reason,
        @JsonProperty("created_at") String createdAt
    ) {}

    public record CreateIssueRequest(
        @NotBlank String title,
        String description,
        String status,
        String priority,
        @JsonProperty("assignee_type") String assigneeType,
        @JsonProperty("assignee_id") String assigneeId,
        @JsonProperty("parent_issue_id") String parentIssueId,
        @JsonProperty("project_id") String projectId,
        @JsonProperty("start_date") String startDate,
        @JsonProperty("due_date") String dueDate,
        @JsonProperty("attachment_ids") List<String> attachmentIds
    ) {}

    public record UpdateIssueRequest(
        String title,
        String description,
        String status,
        String priority,
        @JsonProperty("assignee_type") String assigneeType,
        @JsonProperty("assignee_id") String assigneeId,
        @JsonProperty("parent_issue_id") String parentIssueId,
        @JsonProperty("project_id") String projectId,
        @JsonProperty("start_date") String startDate,
        @JsonProperty("due_date") String dueDate,
        @JsonProperty("attachment_ids") List<String> attachmentIds
    ) {}

    public record CreateProjectRequest(
        @NotBlank String title,
        String description,
        String icon,
        String status,
        String priority,
        @JsonProperty("lead_type") String leadType,
        @JsonProperty("lead_id") String leadId
    ) {}

    public record UpdateProjectRequest(
        String title,
        String description,
        String icon,
        String status,
        String priority,
        @JsonProperty("lead_type") String leadType,
        @JsonProperty("lead_id") String leadId
    ) {}

    public record CreateLabelRequest(@NotBlank String name, @NotBlank String color) {}
    public record UpdateLabelRequest(String name, String color) {}

    public record CreateCommentRequest(
        @NotBlank String content,
        String type,
        @JsonProperty("parent_id") String parentId,
        @JsonProperty("attachment_ids") List<String> attachmentIds
    ) {}

    public record UpdateCommentRequest(
        String content,
        @JsonProperty("attachment_ids") List<String> attachmentIds
    ) {}

    public record MetadataValueRequest(JsonNode value) {}
    public record SubscribeRequest(@JsonProperty("user_type") String userType, @JsonProperty("user_id") String userId) {}

    public static IssueResponse toResponse(IssueEntity issue, List<AttachmentResponse> attachments, List<LabelResponse> labels) {
        return new IssueResponse(
            issue.id,
            issue.workspaceId,
            issue.number,
            issue.identifier,
            issue.title,
            issue.description,
            issue.status,
            issue.priority,
            issue.assigneeType,
            issue.assigneeId,
            issue.creatorType,
            issue.creatorId,
            issue.parentIssueId,
            issue.projectId,
            issue.position,
            issue.startDate,
            issue.dueDate,
            issue.createdAt,
            issue.updatedAt,
            new LinkedHashMap<>(issue.metadata),
            attachments == null ? List.of() : attachments,
            labels
        );
    }

    public static final class IssueEntity {
        public String id = UUID.randomUUID().toString();
        public String workspaceId;
        public int number;
        public String identifier;
        public String title;
        public String description;
        public String status = "todo";
        public String priority = "none";
        public String assigneeType;
        public String assigneeId;
        public String creatorType = "member";
        public String creatorId;
        public String parentIssueId;
        public String projectId;
        public double position;
        public String startDate;
        public String dueDate;
        public String createdAt = now();
        public String updatedAt = createdAt;
        public Map<String, Object> metadata = new LinkedHashMap<>();
        public List<String> attachmentIds = new ArrayList<>();
        public List<String> labelIds = new ArrayList<>();
    }

    public static final class ProjectEntity {
        public String id = UUID.randomUUID().toString();
        public String workspaceId;
        public String title;
        public String description;
        public String icon;
        public String status = "active";
        public String priority = "none";
        public String leadType;
        public String leadId;
        public String createdAt = now();
        public String updatedAt = createdAt;
    }

    public static final class LabelEntity {
        public String id = UUID.randomUUID().toString();
        public String workspaceId;
        public String name;
        public String color;
        public String createdAt = now();
        public String updatedAt = createdAt;
    }

    public static final class CommentEntity {
        public String id = UUID.randomUUID().toString();
        public String issueId;
        public String authorType = "member";
        public String authorId;
        public String content;
        public String type = "comment";
        public String parentId;
        public String createdAt = now();
        public String updatedAt = createdAt;
        public String resolvedAt;
        public String resolvedByType;
        public String resolvedById;
        public List<String> attachmentIds = new ArrayList<>();
    }

    public static final class AttachmentEntity {
        public String id = UUID.randomUUID().toString();
        public String workspaceId;
        public String issueId;
        public String commentId;
        public String chatSessionId;
        public String chatMessageId;
        public String uploaderType = "member";
        public String uploaderId;
        public String filename = "attachment.txt";
        public String url;
        public String contentType = "text/plain";
        public long sizeBytes;
        public String createdAt = now();
    }

    public static final class SubscriberEntity {
        public String issueId;
        public String userType;
        public String userId;
        public String reason = "manual";
        public String createdAt = now();
    }
}
