# Forge

A self-hosted issue tracker for small teams and coding agents. Java 21, Spring Boot, SQLite, SvelteKit and MCP Streamable HTTP run in one application process. Caddy provides the HTTPS entry point.

Production: https://forge.144-31-86-112.sslip.io

Pushes to `main` verify, build and deploy through GitHub Actions. See
[production operations](deploy/README.md) for credentials management, backups,
health checks and rollback.

## Deploy

Requirements: Docker Compose, a domain pointing to the VPS, and Caddy on the host. Open ports 80 and 443 for Caddy; keep port 8080 private.

```sh
cp .env.example .env
# Set DOMAIN, ACME_EMAIL and a random BOOTSTRAP_PASSWORD (12-72 UTF-8 bytes).
docker compose up -d --build
docker compose ps
```

The initial owner is created only when the database has no users. Changing `BOOTSTRAP_PASSWORD` later does not reset that account. Sign in using `BOOTSTRAP_USER` (default `owner`) and the configured password, then change the password in Settings. There is no public registration.

Install the supplied `Caddyfile` in your host Caddy service and configure its `DOMAIN` and `ACME_EMAIL` environment variables. For a manual launch:

```sh
DOMAIN=tracker.example.com ACME_EMAIL=admin@example.com caddy run --config Caddyfile
```

Compose publishes the application only at `127.0.0.1:8080`. If Caddy runs in a separate container, connect it to the application network, set `APP_UPSTREAM=app:8080` and remove the host port mapping. Caddy must be the only public entry point. Set `PUBLIC_ORIGIN` to the exact public origin if deploying without the provided Compose configuration. No CORS configuration is needed.

The container runs as an unprivileged user with a 512 MiB memory limit. Its health check calls `/actuator/health`. The `tracker_data` volume contains the database and images. Startup applies Flyway migrations; shutdown allows in-flight requests to finish for up to 20 seconds.

## First use

1. Create a project with a permanent key, such as `GAME`, and an optional GitHub repository.
2. Add developers and agents under **Settings → People**.
3. Open the gear beside a project in the sidebar. Under **Members**, add existing participants and assign a project role; under **Invitations**, invite new or existing users directly to that project.
4. Create proposals. All tasks start in **Backlog / Pending**.
5. Review them in **Approvals**, then assign or claim approved work and move it to Ready.
6. The assignee starts work, comments and links a PR, then submits it to In Review. A human owner accepts the implementation as Done or requests more work.

Follow an issue to receive updates independently of assignment. Creators and new assignees follow automatically. Notifications are stored in the database and refreshed every minute while the browser is visible. Task lists and boards share search, filters, sorting and pagination. The board shows the current 50-issue page; its column counts describe that page. Task details open without leaving the current view. Keyboard shortcuts are available with `?`.

## MCP

Create an **Agent** participant, grant project access, then create a token under **Settings → Agent tokens**. Save the token when it is displayed; only its SHA-256 hash is stored. Tokens are scoped to one agent and project, can expire, and can be revoked immediately. Their permission cannot exceed current project membership.

Connect a Streamable HTTP MCP client to `https://your-domain/mcp` with `Authorization: Bearer <token>` on every request. Browser sessions do not authenticate MCP. Clients supplying an Origin header must use the configured `PUBLIC_ORIGIN`.

Available tools:

| Read | Write |
| --- | --- |
| `list_tasks`, `search_tasks` | `create_task`, `update_task` |
| `get_task`, `get_task_comments` | `add_comment`, `assign_task` |
| `get_my_tasks`, `get_approved_tasks` | `start_task`, `update_task_status` |
| `get_project_context` | `link_pull_request`, `complete_task` |

Use `get_project_context` without a project to discover authorized projects. Read a task before editing and supply its current `version`. `update_task` replaces the editable fields: preserve fields you are not changing. To claim work, call `assign_task` without an assignee. Move approved work from Backlog to Ready before `start_task`. `complete_task` submits work to **In Review**; it never marks Done. Agents have no approval tool and cannot use status changes to bypass approval. Tool responses contain structured `data` or an `error` with a machine-readable code. Lists use zero-based `page` and `size` (maximum 100).

## Markdown import

Owners can import a local TODO file under **Projects → Import TODO.md**. Preview it before applying. Markdown headings become categories, checkbox items become tasks, and following lines become descriptions. Checked tasks are recorded as approved and completed by the importing owner. Unchecked tasks require approval.

Repeat imports with the same source name, normalized category/title and occurrence number are skipped. Changes to completion or description do not overwrite previously imported tasks. Renaming a title/category or changing the source name creates a new identity. Import at most 200 tasks and 500 KB of Markdown per request. Fenced code blocks are not treated as tasks.

## Development

### Devcontainer (recommended for frontend work)

Install Docker Desktop and the VS Code Dev Containers extension. Open this repository
in VS Code and run **Dev Containers: Reopen in Container** from the command palette.
The container includes Java 21 and Node 22, and installs Codex CLI and frontend dependencies.
To use Codex, run `codex login --device-auth`, follow the sign-in instructions,
then run `codex` from the project root in a container terminal.
In its terminal, run:

```bash
bash .devcontainer/start.sh
```

Open http://localhost:5173. Edit `frontend/src` and save to see changes immediately.
Sign in as `owner` / `local-development-password` on a fresh database.
Local data persists in the workspace's ignored `data/` directory. Ctrl+C stops both
servers. The initial Gradle run downloads dependencies; backend startup may take
longer than Vite. Use localhost port 5173 so the browser origin matches configuration.
Run checks inside the container with `bash gradlew test bootJar` and
`cd frontend && npm run check && npm run build`.

### Local development

Java 21 and Node 22.12+ are required. The Gradle wrapper is included and its distribution checksum is pinned.

```powershell
$env:BOOTSTRAP_PASSWORD = 'choose-a-long-local-password'
$env:COOKIE_SECURE = 'false'
$env:PUBLIC_ORIGIN = 'http://localhost:8080'
.\gradlew.bat bootRun
```

Open `http://localhost:8080`. Gradle builds the frontend and serves it from the application. For frontend hot reload, run `npm ci` and `npm run dev` in `frontend` alongside the backend. Vite proxies `/api` to port 8080. Production cookies are secure by default; disable this only for local HTTP.

SvelteKit uses file-based routes in `frontend/src/routes`, native links and `goto` for navigation. The shared layout preserves workspace state between pages. The static adapter prerenders public page shells into `frontend/dist`; Spring Boot serves each route's generated HTML for direct visits. Authenticated data loads in the browser. Project settings URLs contain runtime project IDs and use the generated `200.html` client fallback, as do unknown page URLs. No Node server runs in production.

Run navigation browser tests with `cd frontend && npx playwright install --with-deps chromium --only-shell && npm run test:e2e`.
To test the generated pages, build the frontend first, then run `PLAYWRIGHT_PREVIEW=1 npm run test:e2e` from `frontend`.

```sh
./gradlew test bootJar
cd frontend
npm run check
```

The runnable application is `build/libs/forge-tracker-1.0.0.jar`. On Windows, use `gradlew.bat`. The build uses system-compatible Gradle launcher encoding for paths containing non-ASCII characters, while Java sources are compiled as UTF-8.

Configuration: `DATA_DIR` (default `./data`), `PORT` (8080), `PUBLIC_ORIGIN`, `COOKIE_SECURE` (true), `BOOTSTRAP_USER`, `BOOTSTRAP_PASSWORD`, and standard JVM `JAVA_TOOL_OPTIONS`.

## Backups and upgrades

Back up the entire data volume, including images. For a consistent simple backup, stop the application, snapshot/copy the volume, then start it again. Copying only `tracker.db` while it is running can omit committed data in its WAL. SQLite-aware online backups are also suitable. Keep a backup before upgrades; migrations are forward-only. Never remove the persistent volume when updating the application.

## Verification

The integration suite exercises real SQLite migrations, competing writes, permissions, approval invalidation, snapshots, subscriptions, notifications, import idempotency, image validation, session/CSRF behavior and actual HTTP MCP calls. It uses an isolated temporary database and never needs a production token.

`scripts/smoke.mjs` runs the complete REST/MCP approval lifecycle against a disposable deployment at `SITE_URL` (default `http://127.0.0.1:18080`). Supply `TEST_USERNAME` and `TEST_PASSWORD`. It creates real test records and saves non-secret identifiers under `.tools`. After restarting that deployment, rerun with `--verify` to check persisted requirements, decisions, comments and image access. Use an isolated database for this test.

Measured locally on Windows with Java 21 and `-Xms64m -Xmx320m`: initial startup 5.06 seconds, approximately 213 MiB working set and 254 MiB private memory after several interactive operations. The application JAR was approximately 44.2 MiB; the frontend JavaScript approximately 59 KB gzip. These observations describe a small local workload; measure your own container under its expected load.

Docker verification with one CPU and a 512 MiB limit measured a 12.1-second first startup and approximately 181 MiB after the HTTP smoke test. Caddy configuration validation, a smoke test through a local Caddy reverse proxy and data verification after container restart passed. Public-domain certificate issuance requires deployment with working DNS and reachable ACME ports.
## Project settings, roles and invitations

Owners open project settings using the gear beside the project name in the sidebar
or the **Project settings** button in the project list. Tabs cover **General**,
**Members**, **Invitations** and **Roles**. Workspace account and agent administration
remain under the global **Settings** menu.

Each project starts with Viewer (READ), Developer (WRITE) and Manager (MANAGE) roles.
Owners can create and edit custom roles with their own names and one of those
permission levels. Role changes immediately update current members, including agent
access, and affect pending invitations. MANAGE permits task management; it never
grants approval, invitation, role management or workspace administration rights.

Create a link under **Project settings → Invitations**, choosing a project role.
Copy it when it appears. Links expire after seven days, work once and can be revoked.
Recipients can register or sign in to an existing account; accepting adds them to
the invited project. Existing project members keep their current role. New accounts
are developers and receive no access to other projects. Invitation secrets are stored
only as SHA-256 hashes and are never returned by invitation lists.

The migration preserves existing project permissions and old workspace invitation
links. Those older links still create an account without assigning project access.
