package ai.multica.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "multica.auth")
public record AuthProperties(
        String jwtSecret,
        String cookieName,
        String csrfCookieName
) {
    public static final String DEFAULT_JWT_SECRET = "multica-dev-secret-change-in-production";
    public static final String DEFAULT_COOKIE_NAME = "multica_auth";
    public static final String DEFAULT_CSRF_COOKIE_NAME = "multica_csrf";

    public AuthProperties {
        jwtSecret = blankToDefault(jwtSecret, DEFAULT_JWT_SECRET);
        cookieName = blankToDefault(cookieName, DEFAULT_COOKIE_NAME);
        csrfCookieName = blankToDefault(csrfCookieName, DEFAULT_CSRF_COOKIE_NAME);
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
