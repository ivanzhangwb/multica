package ai.multica.server.workflow;

import ai.multica.server.workflow.WorkflowModels.AttachmentEntity;
import ai.multica.server.workflow.WorkflowModels.CommentEntity;
import ai.multica.server.workflow.WorkflowModels.IssueEntity;
import ai.multica.server.workflow.WorkflowModels.LabelEntity;
import ai.multica.server.workflow.WorkflowModels.ProjectEntity;
import ai.multica.server.workflow.WorkflowModels.SubscriberEntity;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryWorkflowRepository implements WorkflowRepository {
    private final Map<String, IssueEntity> issues = new LinkedHashMap<>();
    private final Map<String, ProjectEntity> projects = new LinkedHashMap<>();
    private final Map<String, LabelEntity> labels = new LinkedHashMap<>();
    private final Map<String, CommentEntity> comments = new LinkedHashMap<>();
    private final Map<String, AttachmentEntity> attachments = new LinkedHashMap<>();
    private final Map<String, SubscriberEntity> subscribers = new LinkedHashMap<>();

    @Override
    public synchronized List<IssueEntity> listIssues(String workspaceId) {
        return issues.values().stream()
            .filter(i -> workspaceId.equals(i.workspaceId))
            .sorted(Comparator.comparingInt((IssueEntity i) -> i.number).reversed())
            .toList();
    }

    @Override
    public synchronized IssueEntity saveIssue(IssueEntity issue) {
        issues.put(issue.id, issue);
        return issue;
    }

    @Override
    public synchronized Optional<IssueEntity> findIssue(String workspaceId, String issueId) {
        return Optional.ofNullable(issues.get(issueId)).filter(i -> workspaceId.equals(i.workspaceId));
    }

    @Override
    public synchronized void deleteIssue(String workspaceId, String issueId) {
        findIssue(workspaceId, issueId).ifPresent(issue -> {
            issues.remove(issueId);
            comments.values().removeIf(c -> issueId.equals(c.issueId));
            subscribers.values().removeIf(s -> issueId.equals(s.issueId));
            attachments.values().removeIf(a -> issueId.equals(a.issueId));
        });
    }

    @Override
    public synchronized int nextIssueNumber(String workspaceId) {
        return listIssues(workspaceId).stream().mapToInt(i -> i.number).max().orElse(0) + 1;
    }

    @Override
    public synchronized List<ProjectEntity> listProjects(String workspaceId) {
        return projects.values().stream().filter(p -> workspaceId.equals(p.workspaceId)).toList();
    }

    @Override
    public synchronized ProjectEntity saveProject(ProjectEntity project) {
        projects.put(project.id, project);
        return project;
    }

    @Override
    public synchronized Optional<ProjectEntity> findProject(String workspaceId, String projectId) {
        return Optional.ofNullable(projects.get(projectId)).filter(p -> workspaceId.equals(p.workspaceId));
    }

    @Override
    public synchronized void deleteProject(String workspaceId, String projectId) {
        findProject(workspaceId, projectId).ifPresent(project -> projects.remove(projectId));
    }

    @Override
    public synchronized List<LabelEntity> listLabels(String workspaceId) {
        return labels.values().stream().filter(l -> workspaceId.equals(l.workspaceId)).toList();
    }

    @Override
    public synchronized LabelEntity saveLabel(LabelEntity label) {
        labels.put(label.id, label);
        return label;
    }

    @Override
    public synchronized Optional<LabelEntity> findLabel(String workspaceId, String labelId) {
        return Optional.ofNullable(labels.get(labelId)).filter(l -> workspaceId.equals(l.workspaceId));
    }

    @Override
    public synchronized void deleteLabel(String workspaceId, String labelId) {
        findLabel(workspaceId, labelId).ifPresent(label -> labels.remove(labelId));
    }

    @Override
    public synchronized List<CommentEntity> listComments(String issueId) {
        return comments.values().stream()
            .filter(c -> issueId.equals(c.issueId))
            .sorted(Comparator.comparing(c -> c.createdAt))
            .toList();
    }

    @Override
    public synchronized CommentEntity saveComment(CommentEntity comment) {
        comments.put(comment.id, comment);
        return comment;
    }

    @Override
    public synchronized Optional<CommentEntity> findComment(String issueId, String commentId) {
        return Optional.ofNullable(comments.get(commentId)).filter(c -> issueId.equals(c.issueId));
    }

    @Override
    public synchronized Optional<CommentEntity> findCommentById(String commentId) {
        return Optional.ofNullable(comments.get(commentId));
    }

    @Override
    public synchronized void deleteComment(String issueId, String commentId) {
        findComment(issueId, commentId).ifPresent(comment -> {
            comments.remove(commentId);
            attachments.values().removeIf(a -> commentId.equals(a.commentId));
        });
    }

    @Override
    public synchronized AttachmentEntity saveAttachment(AttachmentEntity attachment) {
        attachments.put(attachment.id, attachment);
        return attachment;
    }

    @Override
    public synchronized Optional<AttachmentEntity> findAttachment(String workspaceId, String attachmentId) {
        return Optional.ofNullable(attachments.get(attachmentId)).filter(a -> workspaceId.equals(a.workspaceId));
    }

    @Override
    public synchronized List<AttachmentEntity> listIssueAttachments(String workspaceId, String issueId) {
        return attachments.values().stream()
            .filter(a -> workspaceId.equals(a.workspaceId))
            .filter(a -> issueId.equals(a.issueId))
            .toList();
    }

    @Override
    public synchronized List<AttachmentEntity> listCommentAttachments(String issueId, String commentId) {
        return attachments.values().stream()
            .filter(a -> issueId.equals(a.issueId))
            .filter(a -> commentId.equals(a.commentId))
            .toList();
    }

    @Override
    public synchronized void deleteAttachment(String workspaceId, String attachmentId) {
        findAttachment(workspaceId, attachmentId).ifPresent(attachment -> attachments.remove(attachmentId));
    }

    @Override
    public synchronized List<SubscriberEntity> listSubscribers(String issueId) {
        return subscribers.values().stream().filter(s -> issueId.equals(s.issueId)).toList();
    }

    @Override
    public synchronized SubscriberEntity saveSubscriber(SubscriberEntity subscriber) {
        subscribers.put(subscriber.issueId + ":" + subscriber.userType + ":" + subscriber.userId, subscriber);
        return subscriber;
    }

    @Override
    public synchronized void deleteSubscriber(String issueId, String userType, String userId) {
        subscribers.remove(issueId + ":" + userType + ":" + userId);
    }
}
