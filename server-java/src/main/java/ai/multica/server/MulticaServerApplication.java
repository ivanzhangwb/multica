package ai.multica.server;

import ai.multica.server.auth.AuthProperties;
import ai.multica.server.config.MigrationsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AuthProperties.class, MigrationsProperties.class})
public class MulticaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MulticaServerApplication.class, args);
    }
}
