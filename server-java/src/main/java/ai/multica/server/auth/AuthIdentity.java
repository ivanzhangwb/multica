package ai.multica.server.auth;

public record AuthIdentity(
        String userId,
        String userEmail,
        String agentId,
        String taskId,
        String workspaceId,
        String daemonId,
        ActorSource actorSource,
        String authPath
) {
    public static AuthIdentity human(String userId, String userEmail, String authPath) {
        return new AuthIdentity(userId, userEmail, null, null, null, null, ActorSource.HUMAN, authPath);
    }

    public static AuthIdentity taskToken(String userId, String agentId, String taskId, String workspaceId) {
        return new AuthIdentity(userId, null, agentId, taskId, workspaceId, null, ActorSource.TASK_TOKEN, "task_token");
    }

    public static AuthIdentity cloudPat(String userId) {
        return new AuthIdentity(userId, null, null, null, null, null, ActorSource.CLOUD_PAT, "cloud_pat");
    }

    public static AuthIdentity daemonToken(String workspaceId, String daemonId) {
        return new AuthIdentity(null, null, null, null, workspaceId, daemonId, ActorSource.HUMAN, "daemon_token");
    }
}
