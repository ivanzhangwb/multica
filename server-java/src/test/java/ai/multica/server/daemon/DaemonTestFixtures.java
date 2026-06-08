package ai.multica.server.daemon;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DaemonTestFixtures {
    private DaemonTestFixtures() {}

    static void seedWorkspaceResources(
            DaemonRepository repository,
            String workspaceId,
            List<DaemonModels.RepoData> repos,
            Map<String, Object> settings) {
        repository.saveWorkspaceResources(workspaceId, repos, settings);
    }

    static TaskRecord seedQueuedTask(
            DaemonRepository repository,
            String runtimeId,
            String workspaceId,
            String issueId,
            String agentId) {
        WorkspaceResources resources = repository.workspaceResources(workspaceId);
        TaskRecord task = newSeedTask(runtimeId, workspaceId, issueId, agentId, resources.repos());
        repository.enqueueTask(task);
        return task;
    }

    private static TaskRecord newSeedTask(
            String runtimeId,
            String workspaceId,
            String issueId,
            String agentId,
            List<DaemonModels.RepoData> repos) {
        Instant now = Instant.now();
        return new TaskRecord(
                UUID.randomUUID().toString(),
                agentId,
                runtimeId,
                issueId,
                workspaceId,
                DaemonTaskStatus.QUEUED,
                "",
                DaemonModels.defaultAgent(agentId),
                repos,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "mat_test",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                null);
    }
}
