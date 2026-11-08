# Project instructions

Java 21 / Spring Boot / JdbcClient / SQLite / Flyway backend; Svelte / TypeScript / Vite / Tailwind frontend.

- Keep user data, tokens and credentials out of commits and logs.
- Read additional instructions only when their scope applies. Do not load every instruction file at startup.

## Small, direct UI changes

For small, explicit cosmetic edits, change only the target code immediately. Locate/read only what is needed; skip planning, confirmation, references, skills (including `ui-reference`), and all checks. This overrides project reference/validation rules. Briefly report the edit and skipped checks. Behavior, authorization, data, or dependency changes use the normal workflow.

## Instructions by scope

- Frontend work: `frontend/AGENTS.md` (normal workflow only; the fast path above takes precedence).
- Backend work: `src/AGENTS.md`. Also read it when changing backend build configuration or dependencies.
- MCP server or MCP client work: `.agents/instructions/mcp.md`.
- Deployment, runtime topology, or production initialization: `.agents/instructions/deployment.md`.

For changes spanning scopes, apply the relevant instructions and checks for each affected scope. Documentation-only changes do not require application tests or builds.
