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

### Foundation + Auth + Workspaces (in progress)

- [ ] Monorepo structure, Docker Compose, Nginx, env templates
- [ ] Angular scaffold + landing/login/register shell + backend health check
- [ ] Spring Boot scaffold + Flyway V1 (users, refresh_tokens, workspaces, workspace_members, workspace_invitations)
- [ ] Auth: registration, login/logout, BCrypt, JWT access + refresh rotation
- [ ] Workspaces: CRUD, membership, roles, expiring invitations, authorization
- [ ] Docs: architecture, database design, API contracts, WebSocket events
- [ ] CI workflow (backend + frontend)

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

## Known issues

- (none yet — updated as work lands)

## Verification log

- (build/test results recorded per milestone as they land)
