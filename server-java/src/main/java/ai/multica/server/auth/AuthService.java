package ai.multica.server.auth;

import ai.multica.server.common.ApiException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AuthProperties properties;
    private final TokenRepository tokenRepository;
    private final CsrfVerifier csrfVerifier;

    public AuthService(AuthProperties properties, TokenRepository tokenRepository, CsrfVerifier csrfVerifier) {
        this.properties = properties;
        this.tokenRepository = tokenRepository;
        this.csrfVerifier = csrfVerifier;
    }

    public AuthIdentity authenticate(HttpServletRequest request) {
        TokenSource source = extractToken(request)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "missing authorization"));
        if (source.fromCookie() && !csrfVerifier.verify(request, source.token())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CSRF validation failed");
        }
        return authenticateToken(source.token());
    }

    public AuthIdentity authenticateDaemon(HttpServletRequest request) {
        String token = bearerToken(request.getHeader("Authorization"))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "missing authorization header"));
        if (token.startsWith("mdt_")) {
            return tokenRepository.findDaemonToken(TokenHasher.sha256Hex(token))
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid daemon token"));
        }
        return authenticateToken(token);
    }

    private AuthIdentity authenticateToken(String token) {
        if (token.startsWith("mat_")) {
            return tokenRepository.findTaskToken(TokenHasher.sha256Hex(token))
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid token"));
        }
        if (token.startsWith("mcn_")) {
            return tokenRepository.verifyCloudPat(token)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid token"));
        }
        if (token.startsWith("mul_")) {
            return tokenRepository.findPersonalAccessToken(TokenHasher.sha256Hex(token))
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid token"));
        }
        return verifyJwt(token);
    }

    private AuthIdentity verifyJwt(String token) {
        try {
            var decoded = JWT.require(Algorithm.HMAC256(properties.jwtSecret())).build().verify(token);
            String subject = decoded.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid claims");
            }
            return AuthIdentity.human(subject, decoded.getClaim("email").asString(), "jwt");
        } catch (JWTVerificationException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid token");
        }
    }

    private Optional<TokenSource> extractToken(HttpServletRequest request) {
        Optional<String> bearer = bearerToken(request.getHeader("Authorization"));
        if (bearer.isPresent()) {
            return bearer.map(token -> new TokenSource(token, false));
        }
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> properties.cookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst()
                .map(token -> new TokenSource(token, true));
    }

    private Optional<String> bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authorization.substring("Bearer ".length());
        if (token.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(token);
    }

    private record TokenSource(String token, boolean fromCookie) {
    }
}
