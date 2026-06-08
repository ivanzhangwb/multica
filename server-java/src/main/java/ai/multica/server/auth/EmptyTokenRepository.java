package ai.multica.server.auth;

import java.util.Optional;

final class EmptyTokenRepository implements TokenRepository {
    @Override
    public Optional<AuthIdentity> findPersonalAccessToken(String tokenHash) {
        return Optional.empty();
    }

    @Override
    public Optional<AuthIdentity> findTaskToken(String tokenHash) {
        return Optional.empty();
    }

    @Override
    public Optional<AuthIdentity> findDaemonToken(String tokenHash) {
        return Optional.empty();
    }

    @Override
    public Optional<AuthIdentity> verifyCloudPat(String token) {
        return Optional.empty();
    }

    @Override
    public Optional<Workspace> findWorkspaceBySlug(String slug) {
        return Optional.empty();
    }

    @Override
    public Optional<Member> findMember(String userId, String workspaceId) {
        return Optional.empty();
    }
}
