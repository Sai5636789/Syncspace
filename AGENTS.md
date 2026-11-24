# AGENTS.md — SyncSpace engineering instructions

Read this file before modifying anything in this repository. These rules are
persistent; they outlive any single work session.

## Project

SyncSpace is a real-time collaborative workspace (docs + chat + Kanban).
Master's-level portfolio project. The acceptance bar: two real users on
separate browsers/devices join one workspace, edit a shared document
simultaneously, see each other's cursors, exchange messages, move tasks, and
receive live updates without refreshing — and everything persists after a
restart. Unauthorized users must not reach private workspace resources or
WebSocket events.

## Architecture (short version)

- **Modular monolith**: Spring Boot owns auth, workspaces, membership,
  documents metadata, messaging, tasks, presence, notifications, business
  rules. PostgreSQL is the durable source of truth. Redis handles temporary
  state, caching, presence, and cross-instance event broadcasting (Pub/Sub is
  never a durable store).
- **Collaboration server**: a dedicated Node.js service owns Yjs document
  synchronization, awareness, and CRDT update/snapshot persistence. It never
  owns business rules or authorization decisions — it verifies document
  membership with the backend on every connection.
- **Frontend**: Angular with standalone components, RxJS, Signals where
  appropriate, TipTap + Yjs for documents, STOMP.js for workspace events,
  CDK Drag and Drop for the board.
- Full detail: `docs/architecture.md`. Database: `docs/database-design.md`.
  Contracts: `docs/api-contracts.md`, `docs/websocket-events.md`.

## Rules

- **Verify before claiming.** Run the relevant build and tests after each
  milestone. Never claim a feature works until it has been exercised. Never
  fabricate benchmarks, user counts, or test results. Never silently skip a
  failing test.
- **No placeholders, no fake features.** Do not use simulated users or
  hardcoded responses as substitutes for functional features. Seed data for
  demonstration is fine and must be labeled as seed data. Every visible button
  must perform its intended function.
- **Small, meaningful commits.** Feature-based commit messages and branch
  names only — never "Day 1" / "Day 2" labels. Work on feature branches and
  merge to `main` through pull requests; one PR per milestone area, linked to
  its GitHub issue. Do not rewrite history or create fake merged PRs.
- **Backend enforces authorization.** Workspace and document isolation is
  enforced in backend services and WebSocket subscription checks, not just in
  the frontend. Cross-workspace access prevention has dedicated tests.
- **Secrets.** Never hardcode secrets. Configuration comes from environment
  variables (see `.env.example`). Never log credentials or tokens.
- **Stable dependencies.** Use mutually compatible versions; do not introduce
  a new framework unless technically necessary. Check installed tool versions
  before adding a dependency.
- **Keep the app runnable at every milestone.** Do not remove working features
  while implementing new ones. Ask before making destructive changes.
- **Migrations.** Database changes go through Flyway migrations, never ad-hoc
  DDL. UUID identifiers, foreign keys, unique constraints, timestamps,
  indexes, optimistic-locking `version` columns where concurrent writes occur.
- **Events.** All WebSocket events use the envelope in
  `docs/websocket-events.md` (`eventId`, `eventType`, `workspaceId`,
  `resourceId`, `actorId`, `timestamp`, `version`, `payload`). Broadcast task
  and message events only after the database commit succeeds. Consumers must
  tolerate duplicate events; never claim exactly-once delivery.
- **Persistence.** Persist Yjs binary document updates and snapshots; do not
  rely exclusively on generated HTML. Persist chat messages in PostgreSQL
  before broadcasting. Notifications persist in PostgreSQL.
- **Tests.** JUnit 5 + Mockito for the backend, Testcontainers where a real
  database is needed, Playwright with separate browser contexts for
  multi-user end-to-end scenarios, k6 for WebSocket load tests.

## Local development

- `docker compose -f infrastructure/docker-compose.yml up --build` (full stack)
- Backend: `cd backend && ./mvnw spring-boot:run` (Java 21, Maven 3.9)
- Frontend: `cd frontend && npm install && npm start`
- Collaboration server: `cd collaboration-server && npm install && npm start`
- Known environment limits: Docker containers cannot run in the build VM used
  for this project, so `docker compose up` is validated by syntax check there
  and verified live on a developer machine. Record the verification in
  PROGRESS.md.

## Repository workflow

- Issues track each area of work; PRs reference their issue (`Closes #n`).
- Branch naming: `feature/<area>` (e.g. `feature/auth`, `feature/kanban`).
- Before opening a PR, run: backend `./mvnw test`, frontend `npm run build`
  and `npm test`.
- Update PROGRESS.md with every merged milestone: what landed, build/test
  results, decisions, known issues.
