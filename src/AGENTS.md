# Backend instructions

- Use Java 21, Spring Boot, JdbcClient, SQLite, and Flyway.
- Put domain rules and authorization in services. REST and MCP must use the same rules.
- Never grant agents approval or user-administration permission. MANAGE means project task management only.
- Significant task changes invalidate approval and persist a new immutable revision. Preserve identifiers and optimistic versions.
- Use parameterized SQL and paginated reads.
- Never replace SQLite with an in-memory database in integration tests.
- Run `./gradlew test bootJar` from the repository root; on Windows use `./gradlew.bat test bootJar`.
- Frontend checks are required only when frontend code or shared API contracts also change.
- For MCP changes, also read `.agents/instructions/mcp.md` from the repository root.
