# SyncSpace — Real-Time Collaborative Workspace

A production-style real-time collaborative workspace inspired by Google Docs,
Notion, Slack, and Trello. Multiple real users connect from different browsers
and devices, edit shared documents simultaneously, communicate, manage tasks,
and receive instant updates without refreshing the page.

This is a master's-level Computer Science portfolio project demonstrating
advanced full-stack engineering and distributed systems concepts.

## What it does

- **Workspaces** — create workspaces, invite members, assign roles
  (OWNER / ADMIN / MEMBER / VIEWER), expiring invitation links.
- **Collaborative documents** — Google Docs-style rich-text editing with
  TipTap and Yjs CRDTs: live cursors, simultaneous editing, durable
  persistence of CRDT updates and snapshots.
- **Team messaging** — Slack-style channels, persistent messages, typing
  indicators, delivery acknowledgements, paginated history.
- **Kanban task board** — Trello-style drag-and-drop tasks with optimistic
  locking and live updates for every connected user.
- **Presence** — online/offline indicators, active collaborators, Redis TTL
  heartbeats.
- **Notifications** — real-time, persistent, with unread counts.

## Stack

| Layer | Technology |
| --- | --- |
| Frontend | Angular 22, TypeScript, Angular Material, SCSS, RxJS, Signals, TipTap, Yjs, STOMP.js, CDK Drag and Drop |
| Backend | Java 21, Spring Boot 3.4, Spring Web, WebSocket/STOMP, Security, Data JPA, Data Redis, Bean Validation, Flyway, OpenAPI/Swagger, Maven |
| Data | PostgreSQL 16 (durable source of truth), Redis 7 (Pub/Sub, presence TTL, caching) |
| Collaboration | Node.js Yjs synchronization service (dedicated, CRDT persistence) |
| Infra | Docker Compose, Nginx reverse proxy, GitHub Actions, Spring Boot Actuator |
| Testing | JUnit 5, Mockito, Testcontainers, Playwright, k6 |

No AWS, no paid APIs, no mandatory cloud deployment. Everything runs locally
with Docker Compose.

## Quickstart

```bash
cp .env.example .env   # then edit secrets (JWT_SECRET at minimum)
docker compose -f infrastructure/docker-compose.yml up --build
```

Open http://localhost — register an account, create a workspace, and invite a
second user from another browser to collaborate live.

Two users on the same trusted local network: the second device opens
`http://<host-machine-ip>` (port 80) — see `docs/architecture.md` for the
networking notes.

## Development (without Docker)

```bash
# Backend (needs Java 21 + Maven 3.9; PostgreSQL + Redis on localhost)
cd backend && ./mvnw spring-boot:run

# Frontend (needs Node 22+)
cd frontend && npm install && npm start   # ng serve, proxies /api to :8080

# Collaboration server
cd collaboration-server && npm install && npm start
```

## Repository layout

```text
syncspace/
├── frontend/               Angular app
├── backend/                Spring Boot modular monolith
├── collaboration-server/   Node.js Yjs sync service
├── infrastructure/         docker-compose.yml, nginx/
├── tests/                  e2e (Playwright), load (k6)
├── docs/                   architecture, database design, API + WebSocket contracts
├── .github/workflows/      CI
├── .env.example
├── AGENTS.md               Engineering instructions (read before contributing)
└── PROGRESS.md             Milestones, decisions, known issues
```

## Documentation

- [Architecture](docs/architecture.md)
- [Database design](docs/database-design.md)
- [API contracts](docs/api-contracts.md)
- [WebSocket events](docs/websocket-events.md)

## Status

See [PROGRESS.md](PROGRESS.md). The build is developed in 4 daily slices
(Nov 24–27, 2026), 5 commits per day, each merged to `main` through a pull
request. Nothing is claimed as working until it is verified: frontend
production builds, backend tests, and the multi-browser end-to-end scenario
are run and recorded in PROGRESS.md.
