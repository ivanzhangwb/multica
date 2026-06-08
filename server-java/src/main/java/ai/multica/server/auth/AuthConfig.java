package ai.multica.server.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class AuthConfig {
    @Bean
    TokenRepository tokenRepository() {
        return new EmptyTokenRepository();
    }
}
