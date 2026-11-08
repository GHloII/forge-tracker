# MCP instructions

Read only when implementing or changing MCP server behavior, or using this project's MCP tools as a client.

- The MCP server coordinates tasks only. Never add shell commands, repository mutations or agent-process launching to its tools.
- REST and MCP must use the same service-level domain rules and authorization.
- Never grant agents approval or user-administration permission. MANAGE means project task management only.
- For MCP clients: discover project context, search before creating proposals, read current task versions before editing, wait for human approval, claim/assign before starting, and use `complete_task` to submit work for review.
- Tool schemas are exposed at `/mcp`; supply the individual project-scoped bearer token on every request.
