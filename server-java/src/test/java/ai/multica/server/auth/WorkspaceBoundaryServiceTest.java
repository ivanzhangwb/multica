package ai.multica.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.multica.server.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class WorkspaceBoundaryServiceTest {
    private InMemoryTokenRepository repository;
    private WorkspaceBoundaryService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTokenRepository();
        repository.workspacesBySlug.put("alpha", new Workspace("workspace-alpha", "alpha"));
        repository.members.put(InMemoryTokenRepository.memberKey("user-1", "workspace-alpha"),
                new Member("user-1", "workspace-alpha", "admin"));
        repository.members.put(InMemoryTokenRepository.memberKey("user-1", "workspace-beta"),
                new Member("user-1", "workspace-beta", "member"));
        service = new WorkspaceBoundaryService(repository);
    }

    @Test
    void resolvesSlugBeforeUuidFallbackAndRequiresMembership() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/issues");
        request.addHeader("X-Workspace-Slug", "alpha");
        request.addHeader("X-Workspace-ID", "workspace-beta");

        WorkspaceRequestContext context = service.requireMember(request, AuthIdentity.human("user-1", null, "jwt"));

        assertThat(context.workspaceId()).isEqualTo("workspace-alpha");
        assertThat(context.member().role()).isEqualTo("admin");
    }

    @Test
    void fallsBackToUuidWhenUnknownSlugAndChecksRoles() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/issues");
        request.addHeader("X-Workspace-Slug", "missing");
        request.addHeader("X-Workspace-ID", "workspace-beta");

        WorkspaceRequestContext context = service.requireMember(request, AuthIdentity.human("user-1", null, "jwt"), "member");

        assertThat(context.workspaceId()).isEqualTo("workspace-beta");
        assertThat(context.member().role()).isEqualTo("member");

        assertThatThrownBy(() -> service.requireMember(request, AuthIdentity.human("user-1", null, "jwt"), "owner"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("insufficient permissions");
                });
    }

    @Test
    void taskTokenWorkspaceBindingOverridesClientSuppliedWorkspaceIdentifiers() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/issues");
        request.addHeader("X-Workspace-Slug", "alpha");
        request.addHeader("X-Workspace-ID", "workspace-alpha");
        request.setParameter("workspace_id", "workspace-alpha");
        AuthIdentity taskIdentity = AuthIdentity.taskToken("user-1", "agent-1", "task-1", "workspace-beta");

        WorkspaceRequestContext context = service.requireMember(request, taskIdentity);

        assertThat(context.workspaceId()).isEqualTo("workspace-beta");
    }

    @Test
    void reportsMissingWorkspaceAndMissingMemberWithGoMessages() {
        MockHttpServletRequest missingWorkspace = new MockHttpServletRequest("GET", "/api/issues");
        assertThatThrownBy(() -> service.requireMember(missingWorkspace, AuthIdentity.human("user-1", null, "jwt")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("workspace_id or workspace_slug is required");
                });

        MockHttpServletRequest notMember = new MockHttpServletRequest("GET", "/api/issues");
        notMember.addHeader("X-Workspace-ID", "workspace-gamma");
        assertThatThrownBy(() -> service.requireMember(notMember, AuthIdentity.human("user-1", null, "jwt")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("workspace not found");
                });
    }

    @Test
    void humanActorGuardRejectsMachineCredentialSourcesOnly() {
        service.requireHumanActor(AuthIdentity.human("user-1", null, "jwt"));

        assertThatThrownBy(() -> service.requireHumanActor(AuthIdentity.taskToken("user-1", "agent-1", "task-1", "workspace-1")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("this endpoint is only available to human actors");
                });

        assertThatThrownBy(() -> service.requireHumanActor(AuthIdentity.cloudPat("user-1")))
                .isInstanceOf(ApiException.class);
    }
}
