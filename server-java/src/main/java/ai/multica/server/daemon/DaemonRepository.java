package ai.multica.server.daemon;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

interface DaemonRepository {
    RuntimeRecord upsertRuntime(
            String workspaceId, String daemonId, String name, String provider, String status, String cliVersion);

    List<RuntimeRecord> findRuntimesByWorkspaceAndDaemon(String workspaceId, String daemonId);

    Optional<RuntimeRecord> findRuntime(String runtimeId);

    void markRuntimesOffline(List<String> runtimeIds);

    void recordHeartbeat(String runtimeId, Instant now);

    WorkspaceResources workspaceResources(String workspaceId);

    void saveWorkspaceResources(String workspaceId, List<DaemonModels.RepoData> repos, Map<String, Object> settings);

    Optional<TaskRecord> claimNextTask(String runtimeId);

    Optional<TaskRecord> findTask(String taskId);

    TaskRecord saveTask(TaskRecord task);

    void enqueueTask(TaskRecord task);

    void appendMessages(String taskId, List<DaemonModels.TaskMessageData> messages);

    List<DaemonModels.TaskMessageData> messages(String taskId);

    void appendUsage(String taskId, List<DaemonModels.TaskUsageEntry> usage);
}

record RuntimeRecord(
        String id,
        String workspaceId,
        String daemonId,
        String name,
        String provider,
        String status,
        String cliVersion,
        Instant lastSeenAt) {}

record WorkspaceResources(
        String workspaceId,
        List<DaemonModels.RepoData> repos,
        String reposVersion,
        Map<String, Object> settings) {}

record TaskRecord(
        String id,
        String agentId,
        String runtimeId,
        String issueId,
        String workspaceId,
        DaemonTaskStatus status,
        String workspaceContext,
        DaemonModels.AgentData agent,
        List<DaemonModels.RepoData> repos,
        String projectId,
        String projectTitle,
        List<DaemonModels.ProjectResourceData> projectResources,
        String priorSessionId,
        String priorWorkDir,
        String triggerCommentId,
        String triggerThreadId,
        String triggerCommentContent,
        String triggerAuthorType,
        String triggerAuthorName,
        Integer newCommentCount,
        String newCommentsSince,
        String chatSessionId,
        String chatMessage,
        List<DaemonModels.ChatAttachmentMeta> chatMessageAttachments,
        String autopilotRunId,
        String autopilotId,
        String autopilotTitle,
        String autopilotDescription,
        String autopilotSource,
        Map<String, Object> autopilotTriggerPayload,
        String quickCreatePrompt,
        String squadId,
        String squadName,
        String parentIssueId,
        String parentIssueIdentifier,
        String requestingUserName,
        String requestingUserProfileDescription,
        String authToken,
        String progressSummary,
        Integer progressStep,
        Integer progressTotal,
        String output,
        String error,
        String branchName,
        String sessionId,
        String workDir,
        String failureReason,
        String waitLocalDirectoryReason,
        Instant updatedAt,
        Instant completedAt) {

    TaskRecord withStatus(DaemonTaskStatus nextStatus) {
        Instant now = Instant.now();
        Instant doneAt = switch (nextStatus) {
            case COMPLETED, FAILED, CANCELLED -> now;
            default -> completedAt;
        };
        return copy(nextStatus, progressSummary, progressStep, progressTotal, output, error, branchName, sessionId,
                workDir, failureReason, waitLocalDirectoryReason, now, doneAt);
    }

    TaskRecord withProgress(String summary, Integer step, Integer total) {
        return copy(status, summary, step, total, output, error, branchName, sessionId, workDir, failureReason,
                waitLocalDirectoryReason, Instant.now(), completedAt);
    }

    TaskRecord withWaitingLocalDirectory(String reason) {
        return copy(DaemonTaskStatus.WAITING_LOCAL_DIRECTORY, progressSummary, progressStep, progressTotal, output,
                error, branchName, sessionId, workDir, failureReason, reason, Instant.now(), completedAt);
    }

    TaskRecord withSession(String nextSessionId, String nextWorkDir) {
        return copy(status, progressSummary, progressStep, progressTotal, output, error, branchName,
                blankToCurrent(nextSessionId, sessionId), blankToCurrent(nextWorkDir, workDir), failureReason,
                waitLocalDirectoryReason, Instant.now(), completedAt);
    }

    TaskRecord withComplete(String nextOutput, String nextBranchName, String nextSessionId, String nextWorkDir) {
        return copy(DaemonTaskStatus.COMPLETED, progressSummary, progressStep, progressTotal, nextOutput, error,
                blankToCurrent(nextBranchName, branchName), blankToCurrent(nextSessionId, sessionId),
                blankToCurrent(nextWorkDir, workDir), failureReason, waitLocalDirectoryReason, Instant.now(),
                Instant.now());
    }

    TaskRecord withFail(String nextError, String nextSessionId, String nextWorkDir, String nextFailureReason) {
        return copy(DaemonTaskStatus.FAILED, progressSummary, progressStep, progressTotal, output, nextError,
                branchName, blankToCurrent(nextSessionId, sessionId), blankToCurrent(nextWorkDir, workDir),
                blankToCurrent(nextFailureReason, failureReason), waitLocalDirectoryReason, Instant.now(),
                Instant.now());
    }

    private TaskRecord copy(
            DaemonTaskStatus nextStatus,
            String nextProgressSummary,
            Integer nextProgressStep,
            Integer nextProgressTotal,
            String nextOutput,
            String nextError,
            String nextBranchName,
            String nextSessionId,
            String nextWorkDir,
            String nextFailureReason,
            String nextWaitLocalDirectoryReason,
            Instant nextUpdatedAt,
            Instant nextCompletedAt) {
        return new TaskRecord(id, agentId, runtimeId, issueId, workspaceId, nextStatus, workspaceContext, agent, repos,
                projectId, projectTitle, projectResources, priorSessionId, priorWorkDir, triggerCommentId,
                triggerThreadId, triggerCommentContent, triggerAuthorType, triggerAuthorName, newCommentCount,
                newCommentsSince, chatSessionId, chatMessage, chatMessageAttachments, autopilotRunId, autopilotId,
                autopilotTitle, autopilotDescription, autopilotSource, autopilotTriggerPayload, quickCreatePrompt,
                squadId, squadName, parentIssueId, parentIssueIdentifier, requestingUserName,
                requestingUserProfileDescription, authToken, nextProgressSummary, nextProgressStep, nextProgressTotal,
                nextOutput, nextError, nextBranchName, nextSessionId, nextWorkDir, nextFailureReason,
                nextWaitLocalDirectoryReason, nextUpdatedAt, nextCompletedAt);
    }

    private static String blankToCurrent(String value, String current) {
        if (value == null || value.isBlank()) {
            return current;
        }
        return value;
    }
}
