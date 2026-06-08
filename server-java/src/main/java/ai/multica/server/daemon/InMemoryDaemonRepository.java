package ai.multica.server.daemon;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
class InMemoryDaemonRepository implements DaemonRepository {
    private final Map<String, RuntimeRecord> runtimes = new ConcurrentHashMap<>();
    private final Map<String, WorkspaceResources> workspaceResources = new ConcurrentHashMap<>();
    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, List<DaemonModels.TaskMessageData>> messages = new ConcurrentHashMap<>();
    private final Map<String, List<DaemonModels.TaskUsageEntry>> usage = new ConcurrentHashMap<>();

    @Override
    public RuntimeRecord upsertRuntime(
            String workspaceId, String daemonId, String name, String provider, String status, String cliVersion) {
        Optional<RuntimeRecord> existing = runtimes.values().stream()
                .filter(runtime -> runtime.workspaceId().equals(workspaceId))
                .filter(runtime -> runtime.daemonId().equals(daemonId))
                .filter(runtime -> runtime.provider().equals(provider))
                .findFirst();

        RuntimeRecord next = new RuntimeRecord(existing.map(RuntimeRecord::id).orElseGet(() -> UUID.randomUUID().toString()),
                workspaceId, daemonId, name, provider, status, cliVersion, Instant.now());
        runtimes.put(next.id(), next);
        return next;
    }

    @Override
    public List<RuntimeRecord> findRuntimesByWorkspaceAndDaemon(String workspaceId, String daemonId) {
        return runtimes.values().stream()
                .filter(runtime -> runtime.workspaceId().equals(workspaceId))
                .filter(runtime -> runtime.daemonId().equals(daemonId))
                .sorted(Comparator.comparing(RuntimeRecord::provider))
                .toList();
    }

    @Override
    public Optional<RuntimeRecord> findRuntime(String runtimeId) {
        return Optional.ofNullable(runtimes.get(runtimeId));
    }

    @Override
    public void markRuntimesOffline(List<String> runtimeIds) {
        for (String runtimeId : runtimeIds) {
            RuntimeRecord runtime = runtimes.get(runtimeId);
            if (runtime != null) {
                runtimes.put(runtimeId, new RuntimeRecord(runtime.id(), runtime.workspaceId(), runtime.daemonId(),
                        runtime.name(), runtime.provider(), "offline", runtime.cliVersion(), runtime.lastSeenAt()));
            }
        }
    }

    @Override
    public void recordHeartbeat(String runtimeId, Instant now) {
        RuntimeRecord runtime = runtimes.get(runtimeId);
        if (runtime != null) {
            runtimes.put(runtimeId, new RuntimeRecord(runtime.id(), runtime.workspaceId(), runtime.daemonId(),
                    runtime.name(), runtime.provider(), "online", runtime.cliVersion(), now));
        }
    }

    @Override
    public WorkspaceResources workspaceResources(String workspaceId) {
        return workspaceResources.computeIfAbsent(workspaceId,
                id -> new WorkspaceResources(id, List.of(), reposVersion(List.of()), Map.of()));
    }

    @Override
    public void saveWorkspaceResources(String workspaceId, List<DaemonModels.RepoData> repos, Map<String, Object> settings) {
        List<DaemonModels.RepoData> normalizedRepos = DaemonModels.nonNullRepos(repos).stream()
                .filter(repo -> repo.url() != null && !repo.url().isBlank())
                .distinct()
                .toList();
        workspaceResources.put(workspaceId,
                new WorkspaceResources(workspaceId, normalizedRepos, reposVersion(normalizedRepos),
                        new LinkedHashMap<>(settings == null ? Map.of() : settings)));
    }

    @Override
    public Optional<TaskRecord> claimNextTask(String runtimeId) {
        Optional<TaskRecord> claimable = tasks.values().stream()
                .filter(task -> task.runtimeId().equals(runtimeId))
                .filter(task -> task.status() == DaemonTaskStatus.QUEUED)
                .min(Comparator.comparing(TaskRecord::updatedAt));
        claimable.ifPresent(task -> tasks.put(task.id(), task.withStatus(DaemonTaskStatus.DISPATCHED)));
        return claimable.map(task -> tasks.get(task.id()));
    }

    @Override
    public Optional<TaskRecord> findTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public TaskRecord saveTask(TaskRecord task) {
        tasks.put(task.id(), task);
        return task;
    }

    @Override
    public void enqueueTask(TaskRecord task) {
        tasks.put(task.id(), task);
    }

    @Override
    public void appendMessages(String taskId, List<DaemonModels.TaskMessageData> nextMessages) {
        messages.computeIfAbsent(taskId, ignored -> new ArrayList<>()).addAll(DaemonModels.copyList(nextMessages));
    }

    @Override
    public List<DaemonModels.TaskMessageData> messages(String taskId) {
        return List.copyOf(messages.getOrDefault(taskId, List.of()));
    }

    @Override
    public void appendUsage(String taskId, List<DaemonModels.TaskUsageEntry> nextUsage) {
        usage.computeIfAbsent(taskId, ignored -> new ArrayList<>()).addAll(DaemonModels.copyList(nextUsage));
    }

    private static String reposVersion(List<DaemonModels.RepoData> repos) {
        String joined = repos.stream()
                .map(DaemonModels.RepoData::url)
                .filter(url -> url != null && !url.isBlank())
                .sorted()
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
