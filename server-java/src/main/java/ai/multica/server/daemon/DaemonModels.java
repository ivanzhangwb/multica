package ai.multica.server.daemon;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DaemonModels {
    private DaemonModels() {}

    record RegisterRequest(
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("daemon_id") String daemonId,
            @JsonProperty("legacy_daemon_ids") List<String> legacyDaemonIds,
            @JsonProperty("device_name") String deviceName,
            @JsonProperty("cli_version") String cliVersion,
            @JsonProperty("launched_by") String launchedBy,
            List<RuntimeInput> runtimes) {}

    record RuntimeInput(String name, String type, String version, String status) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record RegisterResponse(
            List<RuntimeView> runtimes,
            List<RepoData> repos,
            @JsonProperty("repos_version") String reposVersion,
            Map<String, Object> settings) {}

    record RuntimeView(String id, String name, String provider, String status) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record HeartbeatRequest(
            @JsonProperty("runtime_id") String runtimeId,
            @JsonProperty("supports_batch_import") Boolean supportsBatchImport) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record HeartbeatResponse(
            String status,
            @JsonProperty("runtime_gone") Boolean runtimeGone,
            @JsonProperty("pending_update") Map<String, Object> pendingUpdate,
            @JsonProperty("pending_model_list") Map<String, Object> pendingModelList,
            @JsonProperty("pending_local_skills") Map<String, Object> pendingLocalSkills,
            @JsonProperty("pending_local_skill_import") Map<String, Object> pendingLocalSkillImport,
            @JsonProperty("pending_local_skill_imports") List<Map<String, Object>> pendingLocalSkillImports) {}

    record WorkspaceReposResponse(
            @JsonProperty("workspace_id") String workspaceId,
            List<RepoData> repos,
            @JsonProperty("repos_version") String reposVersion,
            Map<String, Object> settings) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record RepoData(String url, String description) {}

    record ClaimResponse(TaskView task) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record TaskView(
            String id,
            @JsonProperty("agent_id") String agentId,
            @JsonProperty("runtime_id") String runtimeId,
            @JsonProperty("issue_id") String issueId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("workspace_context") String workspaceContext,
            AgentData agent,
            List<RepoData> repos,
            @JsonProperty("project_id") String projectId,
            @JsonProperty("project_title") String projectTitle,
            @JsonProperty("project_resources") List<ProjectResourceData> projectResources,
            @JsonProperty("prior_session_id") String priorSessionId,
            @JsonProperty("prior_work_dir") String priorWorkDir,
            @JsonProperty("trigger_comment_id") String triggerCommentId,
            @JsonProperty("trigger_thread_id") String triggerThreadId,
            @JsonProperty("trigger_comment_content") String triggerCommentContent,
            @JsonProperty("trigger_author_type") String triggerAuthorType,
            @JsonProperty("trigger_author_name") String triggerAuthorName,
            @JsonProperty("new_comment_count") Integer newCommentCount,
            @JsonProperty("new_comments_since") String newCommentsSince,
            @JsonProperty("chat_session_id") String chatSessionId,
            @JsonProperty("chat_message") String chatMessage,
            @JsonProperty("chat_message_attachments") List<ChatAttachmentMeta> chatMessageAttachments,
            @JsonProperty("autopilot_run_id") String autopilotRunId,
            @JsonProperty("autopilot_id") String autopilotId,
            @JsonProperty("autopilot_title") String autopilotTitle,
            @JsonProperty("autopilot_description") String autopilotDescription,
            @JsonProperty("autopilot_source") String autopilotSource,
            @JsonProperty("autopilot_trigger_payload") Map<String, Object> autopilotTriggerPayload,
            @JsonProperty("quick_create_prompt") String quickCreatePrompt,
            @JsonProperty("squad_id") String squadId,
            @JsonProperty("squad_name") String squadName,
            @JsonProperty("parent_issue_id") String parentIssueId,
            @JsonProperty("parent_issue_identifier") String parentIssueIdentifier,
            @JsonProperty("requesting_user_name") String requestingUserName,
            @JsonProperty("requesting_user_profile_description") String requestingUserProfileDescription,
            @JsonProperty("auth_token") String authToken) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record AgentData(
            String id,
            String name,
            String instructions,
            List<SkillData> skills,
            @JsonProperty("custom_env") Map<String, String> customEnv,
            @JsonProperty("custom_args") List<String> customArgs,
            @JsonProperty("mcp_config") Map<String, Object> mcpConfig,
            String model,
            @JsonProperty("thinking_level") String thinkingLevel) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record SkillData(String id, String name, String description, String content, List<SkillFileData> files) {}

    record SkillFileData(String path, String content) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record ProjectResourceData(
            String id,
            @JsonProperty("resource_type") String resourceType,
            @JsonProperty("resource_ref") Map<String, Object> resourceRef,
            String label) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record ChatAttachmentMeta(
            String id,
            String filename,
            @JsonProperty("content_type") String contentType) {}

    record ProgressRequest(String summary, Integer step, Integer total) {}

    record WaitingLocalDirectoryRequest(String reason) {}

    record CompleteRequest(
            String output,
            @JsonProperty("branch_name") String branchName,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("work_dir") String workDir) {}

    record FailRequest(
            String error,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("work_dir") String workDir,
            @JsonProperty("failure_reason") String failureReason) {}

    record SessionRequest(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("work_dir") String workDir) {}

    record UsageRequest(List<TaskUsageEntry> usage) {}

    record TaskUsageEntry(
            String provider,
            String model,
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens,
            @JsonProperty("cache_read_tokens") long cacheReadTokens,
            @JsonProperty("cache_write_tokens") long cacheWriteTokens) {}

    record MessagesRequest(List<TaskMessageData> messages) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record TaskMessageData(
            int seq,
            String type,
            String tool,
            String content,
            Map<String, Object> input,
            String output) {}

    record StatusResponse(String status) {}

    record IssueGcStatus(String status, @JsonProperty("updated_at") Instant updatedAt) {}

    record ChatSessionGcStatus(String status, @JsonProperty("updated_at") Instant updatedAt) {}

    record AutopilotRunGcStatus(String status, @JsonProperty("completed_at") Instant completedAt) {}

    record TaskGcStatus(String status, @JsonProperty("completed_at") Instant completedAt) {}

    static List<RepoData> nonNullRepos(List<RepoData> repos) {
        return repos == null ? List.of() : List.copyOf(repos);
    }

    static List<ProjectResourceData> nonNullResources(List<ProjectResourceData> resources) {
        return resources == null ? List.of() : List.copyOf(resources);
    }

    static AgentData defaultAgent(String agentId) {
        return new AgentData(agentId, "Agent", "", List.of(), Map.of(), List.of(), Map.of(), null, null);
    }

    static Map<String, Object> copyMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    static <T> List<T> copyList(List<T> value) {
        return value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
