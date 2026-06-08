package ai.multica.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.multica.server.common.ApiException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthServiceTest {
    private static final String SECRET = "test-secret";

    private InMemoryTokenRepository repository;
    private AuthService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTokenRepository();
        service = new AuthService(new AuthProperties(SECRET, null, null), repository, new CsrfVerifier());
    }

    @Test
    void jwtBearerAuthenticatesHumanAndIgnoresClientActorSource() {
        String token = JWT.create()
                .withSubject("user-1")
                .withClaim("email", "user@example.com")
                .withExpiresAt(Instant.now().plusSeconds(3600))
                .sign(Algorithm.HMAC256(SECRET));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Actor-Source", "task_token");

        AuthIdentity identity = service.authenticate(request);

        assertThat(identity.userId()).isEqualTo("user-1");
        assertThat(identity.userEmail()).isEqualTo("user@example.com");
        assertThat(identity.actorSource()).isEqualTo(ActorSource.HUMAN);
        assertThat(identity.authPath()).isEqualTo("jwt");
    }

    @Test
    void cookieJwtRequiresCsrfForStateChangingRequests() {
        String token = JWT.create()
                .withSubject("user-1")
                .withExpiresAt(Instant.now().plusSeconds(3600))
                .sign(Algorithm.HMAC256(SECRET));
        MockHttpServletRequest rejected = new MockHttpServletRequest("POST", "/api/issues");
        rejected.setCookies(new Cookie(AuthProperties.DEFAULT_COOKIE_NAME, token));

        assertThatThrownBy(() -> service.authenticate(rejected))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("CSRF validation failed");
                });

        MockHttpServletRequest accepted = new MockHttpServletRequest("POST", "/api/issues");
        accepted.setCookies(new Cookie(AuthProperties.DEFAULT_COOKIE_NAME, token));
        accepted.addHeader("X-CSRF-Token", csrfToken(token));

        assertThat(service.authenticate(accepted).userId()).isEqualTo("user-1");
    }

    @Test
    void patTaskCloudAndDaemonTokensUseGoPrefixesAndSha256Hash() {
        String pat = "mul_0123456789012345678901234567890123456789";
        repository.personalAccessTokens.put(TokenHasher.sha256Hex(pat), AuthIdentity.human("user-pat", null, "pat"));
        String taskToken = "mat_0123456789012345678901234567890123456789";
        repository.taskTokens.put(TokenHasher.sha256Hex(taskToken),
                AuthIdentity.taskToken("owner-1", "agent-1", "task-1", "workspace-1"));
        String cloudPat = "mcn_0123456789012345678901234567890123456789";
        repository.cloudPats.put(cloudPat, AuthIdentity.cloudPat("cloud-owner"));
        String daemonToken = "mdt_0123456789012345678901234567890123456789";
        repository.daemonTokens.put(TokenHasher.sha256Hex(daemonToken),
                AuthIdentity.daemonToken("workspace-1", "daemon-1"));

        assertThat(service.authenticate(bearer(pat)).userId()).isEqualTo("user-pat");

        AuthIdentity taskIdentity = service.authenticate(bearer(taskToken));
        assertThat(taskIdentity.userId()).isEqualTo("owner-1");
        assertThat(taskIdentity.agentId()).isEqualTo("agent-1");
        assertThat(taskIdentity.taskId()).isEqualTo("task-1");
        assertThat(taskIdentity.workspaceId()).isEqualTo("workspace-1");
        assertThat(taskIdentity.actorSource()).isEqualTo(ActorSource.TASK_TOKEN);

        AuthIdentity cloudIdentity = service.authenticate(bearer(cloudPat));
        assertThat(cloudIdentity.userId()).isEqualTo("cloud-owner");
        assertThat(cloudIdentity.actorSource()).isEqualTo(ActorSource.CLOUD_PAT);

        AuthIdentity daemonIdentity = service.authenticateDaemon(bearer(daemonToken));
        assertThat(daemonIdentity.workspaceId()).isEqualTo("workspace-1");
        assertThat(daemonIdentity.daemonId()).isEqualTo("daemon-1");
        assertThat(daemonIdentity.authPath()).isEqualTo("daemon_token");
    }

    @Test
    void rejectsMissingMalformedAndInvalidTokensWithApiContractMessages() {
        assertThatThrownBy(() -> service.authenticate(new MockHttpServletRequest("GET", "/api/me")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(exception.getMessage()).isEqualTo("missing authorization");
                });

        MockHttpServletRequest malformed = new MockHttpServletRequest("GET", "/api/me");
        malformed.addHeader("Authorization", "Token abc");
        assertThatThrownBy(() -> service.authenticate(malformed))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getMessage()).isEqualTo("missing authorization"));

        assertThatThrownBy(() -> service.authenticate(bearer("not-a-valid-jwt")))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getMessage()).isEqualTo("invalid token"));
    }

    private MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private String csrfToken(String authToken) {
        try {
            byte[] nonce = new byte[16];
            new SecureRandom(new byte[]{1, 2, 3, 4}).nextBytes(nonce);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(nonce) + "." + HexFormat.of().formatHex(mac.doFinal(nonce));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
