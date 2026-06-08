package ai.multica.server.auth;

import ai.multica.server.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceBoundaryService {
    private final TokenRepository tokenRepository;

    public WorkspaceBoundaryService(TokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    public WorkspaceRequestContext requireMember(HttpServletRequest request, AuthIdentity identity, String... roles) {
        String workspaceId = resolveWorkspaceId(request, identity)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "workspace_id or workspace_slug is required"));
        if (identity.actorSource() == ActorSource.TASK_TOKEN && !workspaceId.equals(identity.workspaceId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "task token is bound to a different workspace");
        }
        if (identity.userId() == null || identity.userId().isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "user not authenticated");
        }
        Member member = tokenRepository.findMember(identity.userId(), workspaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "workspace not found"));
        if (roles != null && roles.length > 0 && Arrays.stream(roles).noneMatch(role -> role.equals(member.role()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "insufficient permissions");
        }
        return new WorkspaceRequestContext(workspaceId, member);
    }

    public void requireHumanActor(AuthIdentity identity) {
        if (identity.actorSource().isMachineCredential()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "this endpoint is only available to human actors");
        }
    }

    public Optional<String> resolveWorkspaceId(HttpServletRequest request, AuthIdentity identity) {
        if (identity.actorSource() == ActorSource.TASK_TOKEN) {
            return Optional.ofNullable(identity.workspaceId()).filter(id -> !id.isBlank());
        }
        return resolveSlug(request)
                .or(() -> header(request, "X-Workspace-ID"))
                .or(() -> parameter(request, "workspace_id"));
    }

    private Optional<String> resolveSlug(HttpServletRequest request) {
        return parameter(request, "workspace_slug")
                .or(() -> header(request, "X-Workspace-Slug"))
                .flatMap(slug -> tokenRepository.findWorkspaceBySlug(slug).map(Workspace::id));
    }

    private Optional<String> header(HttpServletRequest request, String name) {
        return Optional.ofNullable(request.getHeader(name)).filter(value -> !value.isBlank());
    }

    private Optional<String> parameter(HttpServletRequest request, String name) {
        return Optional.ofNullable(request.getParameter(name)).filter(value -> !value.isBlank());
    }
}
