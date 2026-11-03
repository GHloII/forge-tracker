# Project conventions

- Java 21, Spring Boot, JdbcClient, SQLite/Flyway; Svelte/TypeScript/Vite/Tailwind frontend.
- One application process and one writable SQLite volume. Caddy is the public reverse proxy.
- Put domain rules and authorization in services. REST and MCP must use the same rules.
- Never grant agents approval or user-administration permission. MANAGE means project task management only.
- Significant task changes invalidate approval and persist a new immutable revision. Preserve identifiers and optimistic versions.
- Use parameterized SQL and paginated reads. Never replace SQLite with an in-memory database in integration tests.
- Keep user data, tokens and credentials out of commits and logs. Do not seed production projects or users beyond the configured initial owner.
- Check `./gradlew test bootJar`; UI: `cd frontend && npm run check && npm run build`. On Windows use `gradlew.bat`.
- For MCP clients: discover project context, search before creating proposals, read current task versions before editing, wait for human approval, claim/assign before starting, and use `complete_task` to submit work for review. Tool schemas are exposed at `/mcp`; supply the individual project-scoped bearer token on every request.
- The MCP server coordinates tasks only. Never add shell commands, repository mutations or agent-process launching to its tools.
