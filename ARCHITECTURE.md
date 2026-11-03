# Architecture

## Boundaries

Forge is a modular monolith. Spring MVC controllers translate REST requests into application service calls. The MCP Java SDK exposes the same task and project services over Streamable HTTP. Services own transactions and authorization. `TaskRepository` builds parameterized, paginated SQL queries; `Database` is a small JdbcClient adapter. There is no ORM, job queue or agent execution subsystem.

`auth` owns browser sessions, agent tokens and centralized permissions. `projects` owns participants and repository context. `tasks` owns task revisions, workflow and image metadata. `notifications` writes activity, subscriptions' notifications and agent audit events. `integrations` contains Markdown import and the MCP transport. The frontend is compiled into Spring static resources. Navigation uses local views and a task hash, so the Java server serves `/` directly without proxy rewrites.

## Permissions

Human owners have workspace administration and approval permissions. Developers and agents have explicit per-project READ, WRITE or MANAGE membership. WRITE allows proposals, comments, subscriptions and editing tasks created by or assigned to the participant. Claiming requires an unassigned approved task. MANAGE allows assignment, archiving and editing other project tasks. Starting/submitting work requires assignment or MANAGE. Only human owners can accept work as Done.

Agent token permission is intersected with current membership. Each request authenticates the hashed token, checks expiration/revocation and binds its actor into the MCP request context. Async servlet dispatches authenticate again. Services recheck token validity before project operations. Approval and administration permissions are unavailable to agents even with MANAGE tokens. MCP tool invocations are audited before dispatch; successful task mutations also write activity.

## Revisions and concurrency

Every mutable task has an optimistic `version`. A write with an old version returns HTTP 409 / MCP CONFLICT. `revision` tracks requirements separately: changing title, Markdown description, type or category creates an immutable requirements snapshot, resets approval to Pending and workflow to Backlog. Priority, labels, assignment, comments, links and subscriptions do not invalidate approval. Approval decisions store reviewer, decision, comment, timestamp and exact revision. No previously reviewed requirements snapshot is overwritten.

Rejected work requires a significant revision before it can be proposed again. Changes Requested may be resubmitted after discussion without changing scope. Only Pending revisions can receive approval decisions. Ready, In Progress, In Review and Done require Approved. A PR link changes neither status nor approval.

Workflow edges:

```text
Backlog -> Ready | Cancelled
Ready -> Backlog | In Progress | Cancelled
In Progress -> Ready | In Review | Cancelled
In Review -> In Progress | Done | Cancelled
Done -> Backlog | Ready
Cancelled -> Backlog
```

The service updates task version, approval, history, snapshots and notifications within one database transaction. Identifiers are allocated by incrementing the project's counter in that transaction. Archiving preserves both identifier and counter.

## SQLite and storage

WAL, foreign keys, a five-second busy timeout and IMMEDIATE transactions are enabled. A one-connection Hikari pool deliberately serializes local database access for this small-team workload. WAL permits independent readers; it does not provide concurrent writers. Deploy exactly one application instance against a local persistent filesystem, not a network filesystem or several replicas. FTS5 indexes task identifiers, titles and descriptions via triggers. Other indexes cover project/status, assignment, approvals, comments and notification lookups.

Task/comment lists use SQL LIMIT/OFFSET. Default task pages contain 50 issues, maximum 100. The board uses the same page rather than downloading every task. Project/member directories are bounded to 100/200 entries for the small-team deployment. Links, images and decision history are bounded to 100 per detail response; uploads stop at 100 images. Revision listings are paginated and snapshot bodies are fetched individually.

Images are PNG/JPEG/GIF, at most 5 MiB and 25 megapixels. Server-side image readers inspect the contents independently of extension/MIME claims. Files use UUID names below the configured attachment directory; original names are display metadata only. Files created by rolled-back uploads are removed. Downloads recheck project access and are private, non-cacheable and `nosniff`. Database and files must be backed up together.

## Notifications and security

Each activity event fans out to current subscribers, explicit recipients and relevant reviewers. Mentions add recipients with project access. The actor is excluded and `(event_id,user_id)` uniqueness prevents duplicate delivery. Notifications from projects no longer accessible to a participant are omitted. The browser polls once a minute while visible.

Spring Security uses BCrypt passwords, session fixation protection, HttpOnly/SameSite=Strict cookies, CSRF on browser writes and a separate stateless bearer-only MCP chain. Human identities are reloaded on API operations, so disabled users lose access. Markdown is rendered with Marked and sanitized by DOMPurify; embedded HTML images/forms/styles are excluded. CSP blocks foreign scripts and frames. GitHub links accept only direct HTTPS PR/commit URLs. No repository access token, shell execution or source synchronization is needed.

Caddy terminates TLS, limits request bodies, preserves Authorization and forwarded metadata, and disables flushing delays on MCP. Application ports are private. Logs do not include token values. Tokens are random 256-bit secrets shown only at creation; only SHA-256 digests are persisted. Agent audit records contain identity, token ID, tool/action and time, never token values or request bodies.
