# SyncSpace — PROGRESS.md

## Plan

4-day compressed build, Nov 24–27, 2026, 5 commits per day. Original 10-day
roadmap compressed:

| Slice | Date | Scope (original days) | Status |
| --- | --- | --- | --- |
| Foundation + Auth + Workspaces | Tue Nov 24 | 1–2 | Done — on GitHub, both PRs merged |
| WebSocket infrastructure + Messaging | Wed Nov 25 | 3–4 | Done — backend verified live (see below); frontend slice is a parallel worktree |
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

### WebSocket infrastructure + Messaging (backend) — DONE Nov 25, 2026 (2 commits, branch `feature/websocket-infra`)

- [x] STOMP endpoint `/ws` (native WebSocket, no SockJS), JWT auth on
  CONNECT, membership-checked subscriptions, 60 msgs/min rate limit
- [x] Event envelope + `RealtimeEventPublisher`: local broadcast via
  `SimpMessagingTemplate` and Redis Pub/Sub fan-out on `syncspace-events`
  (best-effort, at-least-once, consumers dedupe on `eventId`)
- [x] Flyway V2: `channels`, `messages` (soft delete, idempotency on
  `(channel_id, client_message_id)`), `channel_reads` (read pointers)
- [x] Channels + messages REST: list/create channels (unread counts,
  duplicate name 409), mark read, paginated history (chronological,
  page 0 = oldest), edit (author only), soft delete (author or ADMIN+)
- [x] STOMP chat/typing/ack: idempotent send (duplicate
  `clientMessageId` returns the existing message, no second broadcast),
  ephemeral typing, best-effort acks; broadcasts only after DB commit
- [x] Default `general` channel seeded on workspace creation
- [x] Docs: messaging contracts in `api-contracts.md`, event table +
  Redis fan-out section in `websocket-events.md`, V2 tables in
  `database-design.md`

Commits (branch `feature/websocket-infra`):

1. `9e6131a` websocket-infra: STOMP endpoint, JWT CONNECT auth,
   subscription authorization, Redis Pub/Sub fan-out
2. messaging: channels, messages, idempotency, unread tracking, REST +
   STOMP delivery (SHA in git log)

Decisions where the docs were ambiguous (see also `docs/api-contracts.md`):

- Soft-deleted messages are excluded from history entirely (no deleted flag).
- History pages are chronological ASC (page 0 = oldest); `size` is
  clamped to 100 rather than rejected.
- `unreadCount` excludes the caller's own messages; with no read pointer
  it counts all non-own messages.
- `POST /channels/{id}/read` returns 200 `{channelId, lastReadAt}`.
- Ack queue carries `RealtimeEvent` envelopes uniformly: `MESSAGE_SENT`
  (`payload {status: SENT|DUPLICATE, message}`) and
  `MESSAGE_ACKNOWLEDGED` (`payload {messageId, status: "ACKED"}`).
- A truly concurrent duplicate `clientMessageId` may surface as 409;
  retrying then returns the existing message.
- Migration numbering: V2 is messaging (the plan listed V2 as
  documents); documents take a later number.
- Max message body 10,000 chars (STOMP + REST).

Live-probe findings, both fixed in this slice (not left as known issues):

- **CONNECT auth rejected spec-style headers.** The STOMP decoder keeps
  header values verbatim, so a client writing `Authorization: Bearer
  <token>` (space after the colon, as in every STOMP example) arrived as
  `" Bearer <token>"` and failed the `startsWith("Bearer ")` check.
  Spring's own client writes no space, so unit/integration tests never
  saw it; a raw Python STOMP client did. Fixed: the interceptor trims
  the header value; regression test added
  (`connectWithSpaceAfterColonBindsPrincipal`).
- **Redis outage stalled chat acks and REST responses.** Lettuce queues
  commands while reconnecting, so a synchronous Redis publish during an
  outage blocked the caller up to the 60 s command timeout (observed:
  PATCH edit hung past a 10 s client timeout; MESSAGE_SENT ack delayed).
  Fixed: the Redis publish now runs on a bounded single-thread executor
  (queue 100, discard policy), never on the caller's thread. A first
  attempt at failing fast globally (Lettuce `REJECT_COMMANDS`) was
  reverted: it made the listener container's startup subscribe fail and
  took the whole context down.
- **The app did not start with Redis down.** The listener container's
  initial connect is synchronous, so `start()` threw and the failed bean
  start failed the context (the old comment claiming otherwise was
  wrong). Fixed: the container is no longer auto-started
  (`isAutoStartup()` overridden — there is no setter); a
  `RealtimeListenerBootstrap` starts it on `ApplicationReadyEvent` and
  retries every 5 s on a daemon thread until Redis answers.

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
- **The build VM resets between sessions (observed Nov 25, 2026):**
  PostgreSQL's data directory is wiped (only the `postgres` superuser
  remains — recreate the `syncspace` user/database with the repo-default
  password), `/tmp` is cleared (probe scripts, Maven-built state), and
  `pip` installs disappear (reinstall `websocket-client` for the STOMP
  probe). Postgres/Redis daemons also stop; restart both before testing.
  If integration tests fail with "password authentication failed for user
  syncspace" or "Connection to [::1]:5432 refused", check this first.
- **Shell footguns in this VM:** `pkill -f <pattern>` matches the calling
  shell's own command line when the pattern text appears later in the same
  command — it SIGTERMs your own shell. Kill by PID (`fuser -k
  <port>/tcp` for the server) or keep the pattern and the target in
  separate commands. `spring-boot:run` arguments are space-separated, not
  comma-separated: `-Dspring-boot.run.arguments="--server.port=18080
  --logging.level.x=DEBUG"`; a comma-joined value is treated as one
  argument and breaks `server.port` parsing.

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
- **GitHub: foundation + auth + workspaces fully merged Nov 24, 2026.**
  All 5 commits are on `main` (HEAD `fdeab4cc`), PR #5 and PR #6 merged,
  issues #1–#4 closed. Note: the connector pushes file snapshots, not git
  history, so the 5 local commits appear as snapshot commits on GitHub.

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
  - GitHub push verified Nov 24, 2026 ~19:44 EST: all 5 commits on `main`
    (HEAD `fdeab4cc`), PR #5 (merge `5d99cc99`) and PR #6 (merge `fdeab4cc`)
    merged, issues #1–#4 closed. Next slice (WebSocket infrastructure +
    Messaging) starts only on Sai's approval.

- **Nov 25, 2026 — WebSocket infrastructure + Messaging backend complete.**
  - Backend `./mvnw test`: **69/69 pass** (51 after the websocket-infra
    commit — 34 Day 1 + 17 new realtime tests; +18 messaging tests in the
    second commit, including the CONNECT header-space regression test).
    New tests: `MessagingServiceTest` (13, Mockito), `MessagingIntegrationTest`
    (3, real DB + real STOMP client), `RealtimeEventPublisherTest` (5),
    `StompAuthChannelInterceptorTest` (9), `RealtimeIntegrationTest` (4).
  - Live backend boot on port 18080 against local PostgreSQL 16 + Redis
    (both restarted after a VM reset wiped the Postgres data dir):
    Flyway V1+V2 applied. A raw Python STOMP probe (`websocket-client`,
    no Spring client) exercised register → login → create workspace
    (general channel seeded) → create channel → invite/accept member →
    STOMP chat → duplicate send → typing → ack → REST history/edit/delete/
    mark-read: **18/18 checks pass**, including MESSAGE_CREATED broadcast
    to both sessions, MESSAGE_SENT SENT/DUPLICATE acks, TYPING_STARTED
    with display name, MESSAGE_UPDATED/MESSAGE_DELETED broadcasts,
    unreadCount 1 → 0 after mark-read, history totalElements 1 after
    delete. A `redis-cli subscribe syncspace-events` listener captured
    every event type published by the backend (MESSAGE_CREATED ×2,
    MESSAGE_UPDATED, MESSAGE_DELETED, TYPING_STARTED,
    MESSAGE_ACKNOWLEDGED) — backend→Redis fan-out verified live.
  - Redis-down behavior verified live: with Redis stopped mid-run the
    same 18/18 probe passes in ~4 s (local broadcast, acks, and REST all
    responsive — no 60 s stall after the async-publish fix). The backend
    also **starts** with Redis down (health OK; listener bootstrap logs
    the retry warning) and fan-out resumes when Redis returns (fresh
    subscriber captures all event types; probe 18/18 again).
  - Not verified live in this VM: multi-instance fan-out (needs two
    backend instances) and `docker compose up` (containers blocked).
