# SyncSpace — PROGRESS.md

## Plan

4-day compressed build, Nov 24–27, 2026, 5 commits per day. Original 10-day
roadmap compressed:

| Slice | Date | Scope (original days) | Status |
| --- | --- | --- | --- |
| Foundation + Auth + Workspaces | Tue Nov 24 | 1–2 | Done — on GitHub, both PRs merged |
| WebSocket infrastructure + Messaging | Wed Nov 25 | 3–4 | Done — backend + frontend merged (`3d015d8`), tests + docs complete (see below) |
| Collaborative documents + Presence | Thu Nov 26 | 5–6 | Documents done (backend + collab server, this branch); presence is a separate slice, not started here |
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

### WebSocket infrastructure + Messaging — COMPLETE Nov 25, 2026 (frontend + tests + docs)

Backend (`feature/websocket-infra`) and frontend (`1dc3469`,
`feature/messaging-frontend`) were merged into `feature/messaging-frontend`
at `3d015d8`, then finished with a reconnect-recovery test commit and a
docs-alignment commit (SHAs in git log).

- [x] Team Chat page: channel list with unread badges, message history
  (chronological, page/size), optimistic sends with echo dedup, inline
  edit (author only) with confirm-before-delete, typing indicators
- [x] STOMP client service: native WebSocket at `/ws` (no SockJS), bearer
  token on CONNECT, exponential backoff reconnect (1s → 30s cap, jitter),
  re-subscribe + `reconnected$` → REST history recovery, token refresh
  once on auth STOMP error, event dedup on `eventId`, typing timeout
- [x] Reconnect-recovery integration test: a client that disconnects
  recovers messages sent while it was offline from REST history after
  reconnecting (the STOMP simple broker never replays, so no broadcast
  is expected on the fresh subscription)
- [x] Docs aligned to the implemented surface: `websocket-events.md`
  (endpoint `/ws`, no SockJS; MESSAGE_SENT / MESSAGE_ACKNOWLEDGED;
  rate limit as implemented; unbuilt event types and destinations
  marked planned), `api-contracts.md` (full channels/messages
  endpoints, page/size history, PATCH/DELETE rules and error codes;
  documents marked planned), `database-design.md` verified against
  V2 already correct — no change needed

Commits on `feature/messaging-frontend` (see git log for SHAs):

1. `1dc3469` messaging-frontend: Team Chat page, STOMP client, typing,
   unread badges
2. `9e6131a` + `bbd657e` (merged via `3d015d8`) websocket-infra +
   messaging backend (see the backend section above)
3. `9c35015` tests: reconnect recovery and subscription-rejection
   coverage for messaging (1 new integration test; the other checklist
   items — persistence + dedup, CONNECT auth rejection, non-member
   subscription rejection, frontend chat component tests — already had
   real coverage and were verified, not duplicated)
4. docs: websocket-events and api-contracts to the implemented
   messaging surface (SHA in git log)

Decisions for the slice (see also the backend section above):

- Page/size pagination over cursor pagination; history chronological
  ASC (page 0 = oldest); `size` clamped to 100, negative page is 400.
- Native WebSocket at `/ws`, no SockJS.
- A `general` channel is seeded at workspace creation.
- Redis publish is best-effort: local broadcast always happens, fan-out
  resumes when Redis returns; the backend also starts with Redis down.
- Soft-deleted messages are excluded from history entirely.
- Max message body 10,000 chars (STOMP + REST).

Verification (Nov 25, 2026, merged worktree):

- Backend `./mvnw test`: **70/70 pass** (69 + the new reconnect-recovery
  integration test).
- Frontend `npm test -- --watch=false`: **29/29 pass** (12 STOMP
  service, 9 ChatComponent, 4 REST service, 4 pre-existing); no new
  frontend tests were needed — the reconnect-recovery path is covered
  by the STOMP service reconnect test and the ChatComponent
  "recovers from REST after a reconnect" test.
- Frontend `npm run build`: passes; initial bundle **646.02 kB** vs the
  500 kB budget (warning only — up from 573 kB on Nov 24 with the chat
  code and its dependencies).
- Not re-run live in the merged worktree: the 18/18 raw-STOMP probe
  from the backend section (behavior unchanged since; covered by the
  integration suite instead).

### Collaborative documents (backend + Yjs sync server) — DONE Nov 26, 2026 (2 commits, branch `feature/collaborative-docs`)

- [x] Flyway V3: `documents` (optimistic-locking `version`, `@Version`),
  `document_updates` (append-only, BIGINT identity PK),
  `document_snapshots` — exactly as specified in
  `docs/database-design.md`
- [x] Document metadata REST (`com.syncspace.documents`, following the
  messaging module's structure): list/create under a workspace,
  get/rename/delete by id. Non-members get 404, VIEWER gets 403 on
  writes, documents are invisible outside their owning workspace.
  Renames bump `version` (via `saveAndFlush` — the `@Version`
  increment happens at flush) and publish `DOCUMENT_UPDATED` to
  `/topic/workspaces/{id}/documents` only after the DB commit. Deletes
  cascade to updates and snapshots.
- [x] Collaboration server (`collaboration-server/`, Node 22): real Yjs
  sync service on the `y-websocket` protocol, implemented directly on
  `y-protocols` sync + awareness because `y-websocket` 3.x no longer
  ships server utils. Endpoint `ws://host/collab/{documentId}`
  (`?token=…` or `Authorization` header). Every connection verifies
  the JWT signature locally AND document membership with the backend
  (`GET /api/v1/documents/{id}` — 200 means member); anything else
  closes with 4401. Binary updates are relayed between clients (never
  whole-document replacement); awareness (cursors/selections, user
  colors) is broadcast; every update is appended to
  `document_updates` and snapshots go to `document_snapshots`.
- [x] Snapshot policy (documented in `src/collab.js`,
  `src/persistence.js`, `docs/websocket-events.md`): after 50 persisted
  updates, at most once per 60 s for dirty documents, and when the last
  client leaves a document. Loading applies the latest snapshot plus
  all logged updates (re-applying covered updates is harmless — Yjs
  update application is idempotent), so a reconnecting client receives
  only what it missed via its SyncStep1 state vector.
- [x] Env wiring: `collaboration-server/.env.example`, `JWT_SECRET`
  added to the collab service in `infrastructure/docker-compose.yml`
  (compose YAML parses; `docker compose up` cannot run in this VM),
  root `.env.example` gains the collab vars.
- [x] No presence work, no document editor frontend, no Kanban work in
  this slice.

Commits on `feature/collaborative-docs`:

1. `cd58756` documents: metadata REST, Flyway V3, authorization,
   DOCUMENT_UPDATED broadcast
2. collaboration server: Yjs sync + awareness, JWT + membership auth
   (4401), update/snapshot persistence, tests (SHA in git log)

Decisions where the docs were ambiguous:

- List returns `{ "documents": [...] }` with full metadata
  (`workspaceId`, `version`, `createdAt`, `updatedAt`) per
  `api-contracts.md`; `GET /documents/{id}` returns the same DTO.
- `DOCUMENT_UPDATED` is published only on rename (the only metadata
  write); create/delete publish nothing.
- The collab server verifies the JWT with `jsonwebtoken` accepting
  HS256/HS384/HS512: the backend signs with JJWT, which picks the HMAC
  variant from the secret length, and the local default secret (48
  chars) signs HS384 — a HS256-only verifier 4401'd every real token
  (caught by the live check, fixed, regression-tested).
- Frames arriving before authorization finishes are queued server-side
  and replayed in order once accepted; they are never processed
  unauthenticated. (Without this, the client's SyncStep1 sent on open
  was dropped and providers never reported `synced`.)
- Sync replies carry the outer `messageSync` prefix before the
  `readSyncMessage` reply — without it the client misread the reply's
  sync-type byte as the outer message type.
- Persistence failures never break sync: relay happens first, the
  database write second, failures are logged and the room stays dirty
  for the next snapshot attempt. If PostgreSQL is unreachable the
  server still serves sync in memory with persistence reported
  `disabled` on `/health`.
- `npm test` script changed from `node --test test/` to
  `node --test test/*.test.js`: this Node version does not resolve the
  bare directory argument.
- Yjs `Awareness` runs a 3 s housekeeping interval that is only cleared
  by `awareness.destroy()`; the server destroys each room's Awareness
  and Doc when the room drops, and the tests destroy client docs after
  destroying providers — otherwise the test process never exits.

Verification (Nov 26, 2026, this worktree):

- Backend `./mvnw test`: **87/87 pass** (70 pre-existing + 17 new
  document tests: 14 service unit, 3 integration).
- Collab server `npm test`: **6/6 pass** (health, bad token → 4401,
  missing token → 4401, valid token but non-member → 4401, HS384
  token accepted, two-client convergence + awareness + persistence +
  reload after restart).
- Live boot test (real backend :8080 + collab server :3001 against
  real Postgres/Redis): two registered users (Ana, Ben), workspace +
  invite/accept, document created over REST; two real Yjs clients made
  concurrent edits ("Ana was here. " / "Ben was here. ") and both
  converged to identical content `"Ben was here. Ana was here. "`;
  Ben's client received Ana's awareness state; Postgres then held
  **2 rows in `document_updates` and 1 row in `document_snapshots`**
  for the document. Live servers stopped afterwards.

Known issues:

- `docker compose up` still cannot run in this VM (containers blocked);
  the collab service's Compose wiring (`JWT_SECRET`, `DATABASE_URL`,
  `BACKEND_URL`) is validated by YAML parse only and needs a live
  Compose run on a developer machine.
- No document editor frontend yet — TipTap + Yjs UI lands with the
  frontend slice; the sync protocol it needs is the standard
  `y-websocket` one verified here.

### Collaborative documents (frontend: management + TipTap editor) — DONE Nov 26, 2026 (1 commit, branch `feature/collaborative-docs`)

- [x] Document management page (`/workspaces/:id/documents`): lists
  the workspace's documents from REST, creates (title form), renames
  inline (PATCH), deletes with a two-click confirm (DELETE), opens the
  editor. Linked from the dashboard (per-workspace Documents link) and
  the chat header; the editor links back to Documents and Team Chat.
- [x] Document editor page (`/workspaces/:id/documents/:documentId`,
  lazy-loaded so TipTap + Yjs stay out of the initial bundle):
  TipTap bound to a Yjs document over the collab server
  (`y-websocket` WebsocketProvider, shared fragment `content`),
  StarterKit (UndoRedo off — Collaboration ships its own Yjs undo)
  + Underline + Link + Placeholder + Collaboration +
  CollaborationCursor. Toolbar: paragraph, H1–H3, bold, italic,
  underline, bullet/numbered lists, code block, undo/redo, link
  apply/remove with a URL field. Remote cursors and selections render
  from Yjs awareness with per-user colors and name labels; the header
  lists the document's collaborators from the same awareness state.
- [x] `CollabService`: fresh-JWT connect (`?token=…`, refreshes the
  access token first when it is missing or expires within 60 s,
  reusing the auth refresh endpoint), `y-indexeddb` local persistence
  (`syncspace-doc-<id>`, skipped where IndexedDB does not exist) so
  offline edits survive a reload and sync on reconnect, and honest
  save states wired to real provider events — `saving` (local edit
  after the last sync, until the socket flushes), `saved` (provider
  synced, nothing buffered), `offline` (socket unreachable),
  `reconnecting` (dropped after a successful connection), plus
  `connecting` before the first sync. A 4401 close sets `forbidden`
  and the editor goes read-only with a "you don't have access" state
  instead of crashing.
- [x] `DocumentService` (list/create/get/rename/delete) following the
  chat service's shape; `proxy.conf.json` gains `/collab` → :3001
  (ws) so the editor connects same-origin through the dev server.
- [x] Tests: `npm test` **72/72 pass** (41 pre-existing + 31 new:
  document service 5, collab service 12, documents page 6, editor 8
  — including real-TipTap bold/link tests, 4401 forbidden state, and
  saving→saved flush timing). Frontend `npm run build` passes
  (initial 662.86 kB, warning only as before; editor lazy chunk
  545.87 kB). Backend `./mvnw test`: **99/99 pass** (87 pre-existing
  + 12 presence tests from the merged presence slice; no backend
  changes in this commit).

Decisions where the docs were ambiguous:

- **No working `@tiptap/extension-collaboration-cursor` release
  exists for TipTap 3.35.** Its latest (3.0.0, a mislabeled 2.5.0)
  builds on `y-prosemirror`'s cursor plugin, whose sync-plugin key
  is a different instance from the one `@tiptap/y-tiptap`'s
  Collaboration extension uses — the editor crashed at creation
  (`Cannot read properties of undefined (reading 'doc')` in
  `y-prosemirror`'s cursor plugin). Fixed with a small local
  `CollaborationCursor` extension
  (`pages/document-editor/collaboration-cursor.extension.ts`) that
  wires `@tiptap/y-tiptap`'s own `yCursorPlugin` (now a direct
  dependency) with the same caret rendering and awareness-user
  contract; the published package was removed.
- **"Saved" cannot mean "persisted in PostgreSQL".** The client
  cannot observe the server's database write, so `saved` means the
  provider reports synced and the socket has no buffered local update
  outstanding (the server persists every update it receives, relay
  first). Documented on the `SaveState` type in `collab.service.ts`.
- **Collaboration fragment name is `content`**, not the extension's
  default `default` — explicit in the editor config; the name is
  client-side only, the server relays binary updates.
- **The editor host must exist before the editor is created.** The
  host div lives inside the card that only renders once metadata
  loading finishes, while the session connects in the same microtask
  chain — so the editor loads metadata, renders synchronously via
  `ChangeDetectorRef.detectChanges()`, then connects. (Without this
  the editor was silently never created; caught by the component
  tests.)
- **Editor route is lazy-loaded** (`loadComponent`): the eager build
  exceeded the 1 MB initial-budget error (1.21 MB) with TipTap + Yjs
  in the main bundle; lazy loading brings the initial bundle to
  662.86 kB. No budget numbers were changed.
- **Remote cursor/selection CSS lives in `src/styles.scss`, not the
  component stylesheet**: ProseMirror and cursor spans are created at
  runtime, so Angular's emulated view encapsulation would not match
  them from a component stylesheet.
- **A rename by another user does not live-update the documents
  list** — the page re-reads from REST on open. The backend
  broadcasts `DOCUMENT_UPDATED`, but no STOMP subscription to the
  documents topic exists yet.
- The editor's save badge shows `connecting` before the first sync;
  the docs list only `saving`/`saved`/`offline`/`reconnecting`.

Verification (Nov 26, 2026, this worktree):

- Frontend `npm run build`: passes (see above).
  `npm test -- --watch=false`: **72/72 pass**.
- Backend `./mvnw test`: **99/99 pass** (see above; the first run
  showed 3 `PresenceIntegrationTest` failures — "member did not read
  online within 5s" — because this VM's Redis had been started bound
  to 127.0.0.1 only while the backend resolves `localhost` to ::1;
  restarting Redis bound to both families fixed it with no code
  change. The VM restart during this slice had also wiped the
  ephemeral PostgreSQL/Redis installs; both were reinstalled via apt
  and the `syncspace` database/user recreated.)
- Live boot test (real backend :8080 + collab server :3001 + Angular
  dev server :4200, real Postgres/Redis, probe script driving the
  exact REST + Yjs surfaces the editor uses): register Ana + Ben +
  Carol, workspace + invite/accept, document created over REST;
  metadata reads succeed for both members and 404 for the
  non-member; Ana connects through the :4200 `/collab` proxy and Ben
  direct to :3001, both sync; awareness exchanged (both see Ana +
  Ben); concurrent edits ("Ana was here. " / "Ben was here. ")
  converge identically to `"Ana was here. Ben was here. "` with
  identical Yjs state vectors; Carol's collab connection closes with
  **4401**; after disconnect, a fresh client reloads the converged
  content from the server. Postgres then held **2 rows in
  `document_updates` and 1 row in `document_snapshots`** for the
  document. Editor and documents-list routes return **200** via
  :4200, and `/api/v1/health` through the dev-server proxy returns
  ok. **14/14 probe checks pass.** Live servers stopped afterwards.
- Not verified live in this VM: a real-browser click-through of the
  editor (no live browser in this session — the editor UI is covered
  by component tests with the real TipTap editor, and the live probe
  exercised the REST + Yjs protocol the editor uses), `docker
  compose up` (containers blocked), and multi-instance fan-out.

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
- **Maven needs two things after a VM reset (rediscovered Nov 25, 2026):**
  `JAVA_HOME=/home/hatch/.local/jdk21` (Temurin JDK — its truststore
  holds the egress CA) and `/root/.m2/settings.xml` (proxy config).
  Maven reads root's settings file, not `~/.m2/settings.xml`, so after
  a reset restore it from `/home/hatch/.m2/settings.xml` (the copy that
  survives) before running anything: `sudo cp
  /home/hatch/.m2/settings.xml /root/.m2/settings.xml` (or equivalent).
  Without them Maven fails with "Non-resolvable parent POM" / SSL
  errors — check these two first. Run backend tests as
  `JAVA_HOME=/home/hatch/.local/jdk21 ./mvnw test` from `backend/`.
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
- **Frontend bundle budget warning:** initial bundle 646 kB vs the 500 kB
  budget (Angular Material + chat code; was 573 kB on Nov 24). Warning
  only; the build passes.
- **Live `docker compose up` not verified in this VM** (containers blocked);
  verify on a developer machine before claiming the stack runs end to end.
- **Multi-instance Redis fan-out not verified live** (needs two backend
  instances; single-instance publish to `syncspace-events` was captured
  with `redis-cli subscribe`, and local broadcast + resume-after-Redis-down
  were verified — see the Nov 25 verification log).
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

- **Nov 26, 2026 — Workspace presence complete (branch `feature/presence`, 1 commit).**
  - Backend `com.syncspace.presence`: `PresenceService` (Redis per-session
    presence keys, TTL 45 s, heartbeat every 15 s; one key per STOMP
    session so closing one tab never takes a user offline while another
    tab stays open), `PresenceStompController`
    (`/app/workspaces/{id}/presence`, membership-checked, empty `{}` ping),
    `PresenceController` (`GET /workspaces/{id}/presence`, membership
    required, non-members 404), `PresenceDisconnectListener` (fast offline
    path on `SessionDisconnectEvent`), `PresenceSweeper` (`@Scheduled`
    every 15 s; safety net for connections that vanish without a disconnect
    event — broadcasts offline when a key expired, self-heals a stale
    offline state). Broadcasts go out only on transitions, as
    `PRESENCE_CHANGED` envelopes (`{userId, status, lastSeenAt}`) on
    `/topic/workspaces/{id}/presence` via the existing
    `RealtimeEventPublisher`. Redis failures degrade presence to
    "unknown/offline", never fail a request; membership is always checked
    against PostgreSQL first. No Flyway migration — presence is purely
    ephemeral Redis state. Docs updated: `websocket-events.md`
    (PRESENCE_CHANGED + destinations now implemented) and
    `api-contracts.md` (GET /presence + heartbeat destination).
  - Frontend: `PresenceService` (REST list + `formatLastSeen`),
    `ChatStompService` now subscribes to the presence topic, keeps a
    `presence` signal, heartbeats immediately on connect and every 15 s
    while a workspace view is open, and seeds from a REST snapshot.
    Chat sidebar gains a Members card (green dot / "Online" / "Last seen …"
    from real state); dashboard member list shows the same, refreshed
    from REST every 30 s while a workspace is selected (no STOMP there,
    so it does not heartbeat itself). No hardcoded online users anywhere.
  - Backend `./mvnw test`: **82/82 pass** (70 before + 12 new:
    `PresenceServiceTest` 8 Mockito, `PresenceIntegrationTest` 4 against
    real Postgres/Redis/STOMP — heartbeat→online, list reflects it,
    non-member heartbeat rejected with ERROR + 404, one-tab disconnect
    keeps user online / last-tab disconnect broadcasts offline, TTL expiry
    simulated by deleting session keys is picked up by the sweep).
  - Frontend `npm run build`: passes (bundle-budget warning pre-existing,
    initial total 651 kB; no baseline was measured immediately before this
    slice, so no claim is made about how much of that is presence code).
    `npm test -- --watch=false`: **41/41 pass** (29 before + 12
    new: presence service 5, stomp heartbeat/presence 4, chat member list
    2, dashboard presence 1).
  - Live two-user check (backend :8080 + Angular dev server :4200, real
    Postgres/Redis, raw Python STOMP probe, run twice): **16/16 checks
    pass** both times — register Ana + Ben, create workspace,
    invite/accept, both offline before heartbeat, both send heartbeats →
    GET /presence shows both online with lastSeenAt + PRESENCE_CHANGED
    online received for both, Ben disconnects → PRESENCE_CHANGED offline
    received + GET shows Ben offline with lastSeenAt kept and Ana still
    online, non-member GET /presence → 404. Dev-server proxy verified
    (`/api/v1/health` through :4200 → ok).
  - Decisions where the docs were ambiguous: (1) offline detection is
    BOTH a disconnect listener and a TTL-expiry sweep — the docs only
    said "expiration marks users offline" without saying how the broadcast
    happens; keyspace notifications were rejected (needs a Redis server
    setting this service does not own) in favour of a 15 s sweep over the
    membership-bounded user sets. (2) Broadcasts fire only on transitions,
    not per heartbeat, to avoid spamming the topic; late subscribers
    recover from GET /presence. (3) The frontend heartbeat lives in
    `ChatStompService` (one STOMP connection per workspace) rather than a
    second connection, so the presence session id is the chat session id.
    (4) Dashboard presence is a REST snapshot polled every 30 s, not
    live STOMP, because that page opens no STOMP connection.
  - Bug found live and fixed: a REST poll could observe a user online
    with `lastSeenAt: null` (session key written before the last-seen
    hash). Fixed by writing the last-seen hash before the session key and
    falling back to the session key's value in the REST list.
  - Environment notes: this worktree had no `node_modules` — ran `npm ci`
    (299 packages). Backend tests need `SPRING_DATASOURCE_PASSWORD=syncspace`
    in this shell (the DB password is `syncspace`; `application.yml`'s
    default is a different dev password, so without the env var the
    context fails auth). Live servers stopped after verification; Redis
    `presence:*` test keys cleaned up.
  - Not verified live in this VM: multi-instance fan-out, `docker compose
    up` (containers blocked), and a real-browser click-through of the
    member list (presence UI is covered by component tests; the live probe
    exercised the STOMP + REST surface the UI consumes). Document
    collaborator presence (Yjs awareness) is a different slice and was
    not built here.
