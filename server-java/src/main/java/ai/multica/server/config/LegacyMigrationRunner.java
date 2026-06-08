package ai.multica.server.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
@ConditionalOnProperty(prefix = "multica.migrations", name = "enabled", havingValue = "true")
public class LegacyMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyMigrationRunner.class);
    private static final long MIGRATION_ADVISORY_LOCK_KEY = 7244554146635925501L;

    private final DataSource dataSource;
    private final MigrationsProperties properties;

    public LegacyMigrationRunner(DataSource dataSource, MigrationsProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path migrationDir = Path.of(properties.directory()).toAbsolutePath().normalize();
        if (!Files.isDirectory(migrationDir)) {
            throw new IllegalStateException("migration directory not found: " + migrationDir);
        }

        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            lock(connection);
            ensureSchemaMigrationsTable(connection);
            for (Path migration : upMigrations(migrationDir)) {
                applyIfNeeded(connection, migration);
            }
        } finally {
            try {
                unlock(connection);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }

    private List<Path> upMigrations(Path migrationDir) throws IOException {
        try (var stream = Files.list(migrationDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".up.sql"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private void ensureSchemaMigrationsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version TEXT PRIMARY KEY,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        }
    }

    private void applyIfNeeded(Connection connection, Path migration) throws IOException, SQLException {
        String version = migration.getFileName().toString().replaceFirst("\\.up\\.sql$", "");
        if (migrationExists(connection, version)) {
            log.info("skip {} (already applied)", version);
            return;
        }

        if ("103_drop_legacy_daily_rollups".equals(version)) {
            throw new IllegalStateException(
                    "migration 103 requires the Go pre-migration task_usage_hourly hook; run the Go migrator until the hook is ported"
            );
        }

        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO schema_migrations (version) VALUES (?)")) {
            statement.setString(1, version);
            statement.executeUpdate();
        }
        log.info("up {}", version);
    }

    private boolean migrationExists(Connection connection, String version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version = ?)"
        )) {
            statement.setString(1, version);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private void lock(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, MIGRATION_ADVISORY_LOCK_KEY);
            statement.execute();
        }
    }

    private void unlock(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, MIGRATION_ADVISORY_LOCK_KEY);
            statement.execute();
        }
    }
}
