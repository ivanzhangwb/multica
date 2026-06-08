package ai.multica.server.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "multica.migrations")
public record MigrationsProperties(
        boolean enabled,
        @NotBlank String directory
) {
}
