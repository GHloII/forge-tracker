# Deployment instructions

Read only when changing deployment, runtime topology, or production initialization.

- One application process and one writable SQLite volume. Caddy is the public reverse proxy.
- Do not seed production projects or users beyond the configured initial owner.
- Keep user data, tokens and credentials out of commits and logs.
