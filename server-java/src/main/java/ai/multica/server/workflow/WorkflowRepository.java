package ai.multica.server.workflow;

import ai.multica.server.workflow.WorkflowModels.AttachmentEntity;
import ai.multica.server.workflow.WorkflowModels.CommentEntity;
import ai.multica.server.workflow.WorkflowModels.IssueEntity;
import ai.multica.server.workflow.WorkflowModels.LabelEntity;
import ai.multica.server.workflow.WorkflowModels.ProjectEntity;
import ai.multica.server.workflow.WorkflowModels.SubscriberEntity;
import java.util.List;
import java.util.Optional;

public interface WorkflowRepository {
    List<IssueEntity> listIssues(String workspaceId);
    IssueEntity saveIssue(IssueEntity issue);
    Optional<IssueEntity> findIssue(String workspaceId, String issueId);
    void deleteIssue(String workspaceId, String issueId);
    int nextIssueNumber(String workspaceId);

    List<ProjectEntity> listProjects(String workspaceId);
    ProjectEntity saveProject(ProjectEntity project);
    Optional<ProjectEntity> findProject(String workspaceId, String projectId);
    void deleteProject(String workspaceId, String projectId);

    List<LabelEntity> listLabels(String workspaceId);
    LabelEntity saveLabel(LabelEntity label);
    Optional<LabelEntity> findLabel(String workspaceId, String labelId);
    void deleteLabel(String workspaceId, String labelId);

    List<CommentEntity> listComments(String issueId);
    CommentEntity saveComment(CommentEntity comment);
    Optional<CommentEntity> findComment(String issueId, String commentId);
    Optional<CommentEntity> findCommentById(String commentId);
    void deleteComment(String issueId, String commentId);

    AttachmentEntity saveAttachment(AttachmentEntity attachment);
    Optional<AttachmentEntity> findAttachment(String workspaceId, String attachmentId);
    List<AttachmentEntity> listIssueAttachments(String workspaceId, String issueId);
    List<AttachmentEntity> listCommentAttachments(String issueId, String commentId);
    void deleteAttachment(String workspaceId, String attachmentId);

    List<SubscriberEntity> listSubscribers(String issueId);
    SubscriberEntity saveSubscriber(SubscriberEntity subscriber);
    void deleteSubscriber(String issueId, String userType, String userId);
}
