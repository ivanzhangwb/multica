# Server Java Migration Plan

The current backend is not a single service file. It is a Go backend plus CLI/daemon runtime surface with hundreds of handlers, sqlc queries, migrations, realtime fanout, background jobs, and agent process integrations.

This plan keeps the migration reviewable and deployable by moving one vertical slice at a time.

## Target Stack

- Java 21
- Spring Boot 3
- Spring MVC for REST APIs
- Spring JDBC first, JOOQ later if query generation becomes necessary
- PostgreSQL as the existing source of truth
- Redis for realtime relay and multi-node coordination
- Maven for the first module, revisitable if the repository standardizes on Gradle

## Parity Rules

- Reuse the existing PostgreSQL schema and `schema_migrations` table.
- Keep public API paths and JSON shapes stable for web, desktop, mobile, and CLI clients.
- Port tests alongside each module before routing production traffic to Java.
- Do not run Java migrations in production until the Go migrator hooks have Java equivalents.
- Keep the Go server available until each vertical slice passes contract tests.

## Migration Order

1. Runtime baseline: HTTP server, config, health checks, DB connectivity, logging, metrics shape.
2. Auth and workspace boundary: JWT/cookie/PAT parsing, workspace membership guards, actor-source guards.
3. Read-only issue/project/label APIs: list/get/search endpoints with existing SQL behavior.
4. Mutating issue/comment APIs: validation, subscriber/activity fanout, realtime invalidation.
5. Agent/runtime/task APIs: daemon auth, task claim lifecycle, task message ingestion, cancellation.
6. Realtime and daemon websocket hubs: browser WS, daemon WS, Redis sharded relay.
7. Background jobs: runtime sweeper, heartbeat batching, autopilot scheduler, task usage rollups.
8. Integrations: GitHub webhooks, Lark installation/inbound/outbound, billing webhooks.
9. CLI migration or compatibility: keep Go CLI temporarily, then port command groups to Java or split the CLI as a client-only artifact.
10. Cutover: dual-run contract checks, switch traffic, archive Go server after rollback window.

## Current Java Baseline

`server-java/` currently provides a compileable and test-covered contract baseline:

- `MulticaServerApplication`
- `/health`
- `/readyz`
- `/healthz`
- config binding for database and migrations
- guarded legacy migration runner
- auth/workspace boundary services for JWT, auth cookie, PAT, daemon token, task token, workspace lookup, membership, and human-only actor guards
- issue/project/label/comment/subscriber/metadata/attachment controller contracts backed by an in-memory repository
- daemon/runtime/task controller contracts backed by an in-memory repository
- tests for health readiness, auth/workspace boundaries, workflow API shape, and daemon lifecycle shape

The next useful implementation slice is replacing the in-memory repositories with PostgreSQL/JDBC implementations that reuse the existing schema and query behavior. Until that is done, this module is a migration target and contract harness, not a production-equivalent replacement for the Go server.
