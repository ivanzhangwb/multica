# Multica Server Java

This module is the Java migration target for the current Go server in `server/`.

The current milestone establishes a reviewable Java contract baseline:

- Java 21
- Spring Boot HTTP server
- PostgreSQL JDBC access
- `/health`, `/readyz`, and `/healthz` endpoints matching the Go server contract
- Optional migration runner compatible with the existing `schema_migrations` table
- Auth/workspace boundary service contracts for JWT, auth cookie, PAT, daemon token, and task token flows
- In-memory issue/project/label/comment API surface matching the main Go JSON shape
- In-memory daemon/runtime/task API surface matching the daemon client JSON shape

Run locally:

```bash
cd server-java
mvn test
mvn spring-boot:run
```

The service defaults to port `8080` and the same local database name used by the Go backend.

Migration execution is disabled by default:

```bash
MULTICA_JAVA_RUN_MIGRATIONS=true mvn spring-boot:run
```

Keep it disabled in shared or production databases until the Go migrator's pre-migration hooks have been ported. The Java runner intentionally refuses migration `103_drop_legacy_daily_rollups` because the current Go path performs an idempotent task usage backfill before that SQL file.

This module is not yet a production replacement for the Go server. The business APIs and daemon lifecycle currently use in-memory repositories so controllers, DTOs, validation, and tests can stabilize before wiring the existing PostgreSQL schema, Redis liveness, realtime fanout, and background jobs.
