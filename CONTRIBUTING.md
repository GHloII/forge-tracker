# Contributing

Use Java 21 and Node 22.12+. Build with the checked-in Gradle wrapper. SQLite is the only database, including in integration tests.

Run `./gradlew test bootJar` before submitting backend changes. Run `npm run check` and `npm run build` in `frontend` for UI changes. Tests must assert observable behavior; approval, authorization, persistence and optimistic concurrency changes need integration coverage. Validate `Caddyfile` with `caddy validate --config Caddyfile --adapter caddyfile` when modifying deployment settings. Test with a separate data directory and never reuse production tokens or credentials.

Use standard Java formatting and Prettier with the frontend's configuration. Keep controllers thin, application rules in services, and SQL parameterized. REST and MCP must share services. Add new schema changes as numbered Flyway migrations; never modify an already deployed migration. Keep approval revision semantics and identifier stability backward compatible.

Use conventional descriptive commits. Explain the behavior changed, validation actually performed, and any remaining deployment or manual checks. Keep secrets, local data, caches, generated builds and test artifacts outside version control. Avoid adding infrastructure or integrations beyond the tracker scope.

The production frontend dependency audit currently reports no vulnerabilities. The full development dependency audit reports a low-severity `deepmerge` prototype-poisoning advisory through the Svelte Vite plugin, with no fixed version available in that audit. This dependency runs during development/build and is absent from the runtime image. Recheck it when updating the frontend toolchain.
