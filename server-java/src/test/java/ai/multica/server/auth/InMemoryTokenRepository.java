package ai.multica.server.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

class InMemoryTokenRepository implements TokenRepository {
    final Map<String, AuthIdentity> personalAccessTokens = new HashMap<>();
    final Map<String, AuthIdentity> taskTokens = new HashMap<>();
    final Map<String, AuthIdentity> daemonTokens = new HashMap<>();
    final Map<String, AuthIdentity> cloudPats = new HashMap<>();
    final Map<String, Workspace> workspacesBySlug = new HashMap<>();
    final Map<String, Member> members = new HashMap<>();

    @Override
    public Optional<AuthIdentity> findPersonalAccessToken(String tokenHash) {
        return Optional.ofNullable(personalAccessTokens.get(tokenHash));
    }

    @Override
    public Optional<AuthIdentity> findTaskToken(String tokenHash) {
        return Optional.ofNullable(taskTokens.get(tokenHash));
    }

    @Override
    public Optional<AuthIdentity> findDaemonToken(String tokenHash) {
        return Optional.ofNullable(daemonTokens.get(tokenHash));
    }

    @Override
    public Optional<AuthIdentity> verifyCloudPat(String token) {
        return Optional.ofNullable(cloudPats.get(token));
    }

    @Override
    public Optional<Workspace> findWorkspaceBySlug(String slug) {
        return Optional.ofNullable(workspacesBySlug.get(slug));
    }

    @Override
    public Optional<Member> findMember(String userId, String workspaceId) {
        return Optional.ofNullable(members.get(memberKey(userId, workspaceId)));
    }

    static String memberKey(String userId, String workspaceId) {
        return userId + ":" + workspaceId;
    }
}
