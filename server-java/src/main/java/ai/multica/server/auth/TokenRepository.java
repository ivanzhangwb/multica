package ai.multica.server.auth;

import java.util.Optional;

public interface TokenRepository {
    Optional<AuthIdentity> findPersonalAccessToken(String tokenHash);

    Optional<AuthIdentity> findTaskToken(String tokenHash);

    Optional<AuthIdentity> findDaemonToken(String tokenHash);

    Optional<AuthIdentity> verifyCloudPat(String token);

    Optional<Workspace> findWorkspaceBySlug(String slug);

    Optional<Member> findMember(String userId, String workspaceId);
}
