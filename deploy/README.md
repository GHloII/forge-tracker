# Production deployment

Live service: https://forge.144-31-86-112.sslip.io

Every push to `main` runs the Java/SQLite/MCP integration tests and Svelte checks,
packages the UI and JAR on GitHub Actions, builds a runtime image, and transfers it
over host-key-verified SSH. Pull requests run verification only. The VPS does not
compile code and no registry credentials are required.

Repository secrets: `DEPLOY_SSH_KEY` (a dedicated forced-command key),
`DEPLOY_KNOWN_HOSTS` (the verified VPS host keys). Repository variables:
`DEPLOY_HOST`, `PUBLIC_URL`. Never copy these secrets into Git.

Server directory: `/opt/forge-tracker`. Its `.env` contains the initial owner
credentials and public origin. `.release.env` identifies the deployed image;
`deployed-revision` records the healthy commit. The SQLite database and images
live in the persistent Docker volume `forge-tracker-data`.

The existing Caddy proxy routes the Forge hostname to `forge-tracker:8080` on
the existing Docker network. The application exposes no public host port.

`deploy.sh` is installed separately on the server and is the only command allowed
for the CI SSH key. Changes to that script or production Compose configuration
require an operator to inspect and install them; pushes deploy application images.

Deployments are serialized, SHA-256 verified, and health checked. Before replacing
an existing container, its data is backed up while stopped. Three backups and the
previous image are retained. An unhealthy new container triggers a return to the
previous image. Database migrations must remain backward compatible: automatic
image rollback deliberately does not overwrite the database or new user data.
For an incompatible migration, stop the app and restore a chosen backup manually.
Backups on this VPS are not a substitute for off-server backups.

Manual image rollback (on the server, after checking migration compatibility):

```sh
cd /opt/forge-tracker
printf 'FORGE_IMAGE=forge-tracker:<previous-commit>\n' > .release.env
docker compose --env-file .release.env -f compose.yml up -d --no-build
```

Never run `docker compose down -v`. To rotate CI access, replace the dedicated
authorized-key entry and repository SSH secret. The initial owner should change
their password through the application after first login.
