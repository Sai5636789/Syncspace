# SyncSpace — PROGRESS.md

## Plan

4-day compressed build, Nov 24–27, 2026, 5 commits per day. Original 10-day
roadmap compressed:

| Slice | Date | Scope (original days) | Status |
| --- | --- | --- | --- |
| Foundation + Auth + Workspaces | Tue Nov 24 | 1–2 | In progress |
| WebSocket infrastructure + Messaging | Wed Nov 25 | 3–4 | Not started |
| Collaborative documents + Presence | Thu Nov 26 | 5–6 | Not started |
| Kanban + Notifications + Dashboard + Testing + Delivery | Fri Nov 27 | 7–10 | Not started |

## Milestones

### Foundation + Auth + Workspaces — DONE Nov 24, 2026 (5 commits)

- [x] Monorepo structure, Docker Compose, Nginx, env templates
- [x] Angular scaffold + landing/login/register shell + backend health check
- [x] Spring Boot scaffold + Flyway V1 (users, refresh_tokens, workspaces, workspace_members, workspace_invitations)
- [x] Auth: registration, login/logout, BCrypt, JWT access + refresh rotation
- [x] Workspaces: CRUD, membership, roles, expiring invitations, authorization
- [x] Docs: architecture, database design, API contracts, WebSocket events
- [x] CI workflow (backend + frontend)

Commits (branch `feature/project-foundation` for 1–3, `feature/auth-workspaces` for 4–5 — see git log):

1. `bec2b2b` project-foundation: monorepo layout, docs, CI, Docker Compose, collab server scaffold
2. `b5f226d` backend-foundation: Spring Boot scaffold, Flyway V1, health endpoint, security baseline
3. `2d94aeb` frontend-shell: Angular app, Material theme, auth pages, dashboard, backend status probe
4. `119bf19` auth: JWT registration/login, refresh rotation, protected routes
5. workspaces: CRUD, roles, invitations, dashboard wiring (SHA in git log)

## Architecture decisions

- **Modular monolith + dedicated Yjs service.** Spring Boot owns business
  rules and persistence; a small Node.js service owns Yjs sync only. No
  microservice sprawl; the two services share PostgreSQL (via the backend
  for auth) and Redis.
- **Angular 22 / Spring Boot 3.4 / Java 21 / PostgreSQL 16 / Redis 7 /
  Node 22.** Latest stable, mutually compatible versions available at build
  time (verified Nov 24, 2026). Vitest is the Angular 22 default test runner
  (no Chrome needed for unit tests).
- **JWT access (15 min) + rotating refresh tokens (7 days)** stored hashed
  in PostgreSQL; refresh rotation invalidates the used token.
- **PostgreSQL is the source of truth; Redis Pub/Sub is transport only.**
  Presence uses Redis TTL + heartbeats. Missed events recover from
  PostgreSQL after reconnect.

## Environment notes

- Build VM (Nov 24, 2026): Node v24.22.1, npm 10.9.4, git 2.43.0. Java,
  Maven, and Docker were NOT preinstalled — Temurin JDK 21.0.13 and Maven
  3.9.11 installed locally under `~/.local` for the build.
- Docker containers are blocked in this VM (`setns: operation not
  permitted`), so `docker compose up` cannot be verified here. Compose file
  syntax is validated; the live run must be verified on a developer machine.
- **Build-VM network quirks (all fixed in-tree, see below):**
  - Maven could not reach Maven Central until the JDK truststore gained the
    egress CA bundle, `~/.m2/settings.xml` pointed at the `hatch-egress-proxy`
    (root's `/root/.m2/settings.xml` is the one Maven reads), and
    `-Djava.net.preferIPv6Addresses=true` was set (Java TCP to 127.0.0.1 is
    intercepted by the sandbox egress redirect; `::1` reaches local services).
    The flag is in `backend/.mvn/jvm.config` and the surefire `argLine`.
  - Local apt PostgreSQL 16 serves SSL with a self-signed snakeoil cert, so
    the dev JDBC URL uses `sslmode=disable` (Docker Postgres has SSL off).
    The dev URL also uses `[::1]` instead of `localhost` for the reason above.
    Docker Compose overrides the URL via `SPRING_DATASOURCE_URL`.
  - Spring Boot 3.4.1 manages Flyway 10.20.1, which rejects PostgreSQL 16.
    The pom pins Flyway 11.16.0 and adds `flyway-database-postgresql`
    (Flyway 10+ ships database support separately).
  - PostgreSQL 16 was installed via apt and is running locally on port 5432
    (database/user `syncspace`); the backend was verified live against it.

## Known issues

- **CI workflow not pushed/verified.** `.github/workflows/ci.yml` is in the
  repo (commit 1) but the Muse GitHub App cannot push workflow files
  (GitHub 403, seen on RouteMind). It must be added by hand on github.com
  or pushed from a machine with workflow permission; it has not run yet.
- **No email delivery yet.** Invitation tokens are returned in the API
  response for the inviter to share; email sending lands with notifications.
- **Whole-family refresh-token revocation on reuse is not implemented** —
  reuse of a revoked token is rejected with 401, but sibling tokens are not
  revoked.
- **Frontend bundle budget warning:** initial bundle 573 kB vs the 500 kB
  budget (Angular Material). Warning only; the build passes.
- **Live `docker compose up` not verified in this VM** (containers blocked);
  verify on a developer machine before claiming the stack runs end to end.
- **GitHub issues/PRs not created yet.** The first issue-creation attempt
  timed out waiting for approval (approval expired, nothing was created).
  Issues #1–#4 and the PRs must be created once the GitHub write is
  approved — the local branches carry the work.

## Verification log

- **Nov 24, 2026 — Day 1 complete.**
  - Backend `./mvnw test`: **34/34 pass** (health slice, auth service/JWT/
    controller/integration, workspace service/integration).
  - Frontend `npm run build`: passes (one bundle-budget warning, 573 kB).
    `npm test -- --watch=false`: **4/4 pass** (app shell, dashboard).
  - Live backend boot against local PostgreSQL 16: `GET /api/v1/health`
    → `{"status":"ok","service":"syncspace-backend"}`, actuator 200,
    Flyway V1 applied (users, refresh_tokens, workspaces,
    workspace_members, workspace_invitations). Live register/login
    exercised with curl; anonymous `/me` correctly returns 401.
  - `docker-compose.yml` validated by YAML parse (6 services, pgdata +
    redisdata volumes). `docker compose up` NOT run (blocked in this VM).
  - Full auth + workspace flow verified by `WorkspaceIntegrationTest`
    (create → invite → accept → role checks → transfer → delete).
  - Live end-to-end through the Angular dev server (`npm start`, proxy
    to the backend): register → 201, create workspace → 201 (OWNER),
    list workspaces/members → correct, anonymous list → 401. (Found and
    fixed during this check: `proxy.conf.json` was never referenced —
    `angular.json` now sets `serve.options.proxyConfig`.)
