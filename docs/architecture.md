# SyncSpace Architecture

## Overview

SyncSpace is a real-time collaborative workspace: shared documents, team
chat, and a Kanban board, with live updates for every connected user. The
system is a **modular monolith** (Spring Boot) plus one dedicated
**Yjs synchronization service** (Node.js). Everything runs locally with
Docker Compose; there is no AWS, no paid API, and no mandatory cloud
deployment.

```
                ┌──────────────────────────────────────────────────┐
                │  Nginx (port 80)                                 │
                │  /            → frontend                          │
                │  /api/ /ws/  → backend                           │
                │  /collab/     → collaboration-server             │
                └──────┬───────────────┬───────────────┬───────────┘
                       │               │               │
            ┌──────────▼──────┐ ┌──────▼───────┐ ┌────▼───────────┐
            │ Angular 22 SPA  │ │ Spring Boot  │ │ Node.js Yjs    │
            │ Material, RxJS, │ │ 3.4 / Java 21│ │ sync service   │
            │ TipTap, Yjs,    │ │ REST + STOMP │ │ (y-websocket   │
            │ STOMP.js, CDK   │ │ WebSocket    │ │ protocol)      │
            └─────────────────┘ └──────┬───────┘ └────┬───────────┘
                                     │                │
                          ┌──────────▼────┐  ┌────────▼────────┐
                          │ PostgreSQL 16 │  │ Redis 7         │
                          │ source of     │  │ Pub/Sub, TTL    │
                          │ truth         │  │ presence, cache │
                          └───────────────┘  └─────────────────┘
```

## Component responsibilities

### Angular frontend (`frontend/`)

- Standalone components, Angular Router with auth guards, Angular Material
  UI, SCSS, RxJS + Signals.
- **Documents**: TipTap editor bound to a Yjs document; remote cursors and
  selections via Yjs awareness; IndexedDB local persistence for offline
  reloads.
- **Messaging / tasks / presence**: STOMP.js client over the backend
  WebSocket, with automatic reconnection and exponential backoff, event
  deduplication by `eventId`, and recovery of durable state from REST after
  reconnect.
- **API layer**: typed `HttpClient` services per domain; JWT access token
  attached by an interceptor; silent refresh on 401 using the refresh-token
  endpoint.

### Spring Boot backend (`backend/`)

Modular monolith, feature-based packages:

```text
com.syncspace
├── auth            registration, login/logout, JWT, refresh rotation
├── users           profiles
├── workspaces      workspaces, memberships, invitations
├── documents       document metadata + membership (content lives in Yjs)
├── messaging       channels, messages
├── tasks           Kanban tasks, optimistic locking
├── presence        presence aggregation over Redis
├── notifications   persistent notifications
├── realtime        STOMP config, event envelope, Redis Pub/Sub fan-out
├── audit           audit logs
├── common          errors, DTOs, pagination, security helpers
└── configuration   application configuration
```

Owns authentication, authorization, workspace management, membership,
tasks, messages, notifications, and all business rules. Enforces
workspace/document isolation on every request and every WebSocket
subscription — the frontend never decides access.

### Yjs collaboration server (`collaboration-server/`)

Node.js service owning collaborative document synchronization: Yjs CRDT
updates, awareness (cursors/selections), and persistence of binary document
updates and snapshots to PostgreSQL (`document_updates`,
`document_snapshots`). It verifies document membership with the backend on
every connection (JWT + membership check) and never makes authorization
decisions of its own. It does not own business rules.

### PostgreSQL 16

Durable source of truth for users, refresh tokens, workspaces, memberships,
invitations, documents and their metadata, channels, messages, tasks,
notifications, audit logs, and (later) the transactional outbox. Flyway
migrations, UUID primary keys, foreign keys, unique constraints, indexes.

### Redis 7

- **Pub/Sub**: distributes committed domain events between backend
  instances so a second instance does not double-broadcast. Delivery is
  not guaranteed; clients recover durable state from PostgreSQL.
- **Presence**: per-session keys with TTL, refreshed by heartbeats;
  expiration marks users offline. One key per connection so closing one
  tab does not mark a user offline while another tab stays open.
- **Caching**: read-through caches where safe (workspace membership lists,
  document metadata); always invalidated on write.

## Request flows

### Login

1. Angular posts credentials to `POST /api/v1/auth/login`.
2. Backend verifies BCrypt hash, issues a short-lived JWT access token
   (15 min) and a refresh token (7 days, stored hashed, single-use).
3. Angular stores the access token in memory and the refresh token in
   `httpOnly` cookie-equivalent storage (refresh token is sent only to
   `/api/v1/auth/refresh`); the interceptor attaches the access token and
   silently refreshes on 401.

### Sending a chat message

1. Client sends a STOMP message to `/app/workspaces/{id}/chat` with a
   client-generated idempotency key.
2. Backend validates membership, persists the message in PostgreSQL in the
   same transaction as the idempotency record, then publishes the event
   envelope to Redis Pub/Sub.
3. Every backend instance receives it and broadcasts to
   `/topic/workspaces/{id}/messages`. Clients deduplicate by `eventId` and
   acknowledge delivery (`/user/queue/acknowledgements`).
4. On reconnect, clients page message history from REST to recover anything
   missed.

### Collaborative editing

1. Client opens a document: REST `GET /api/v1/documents/{id}` returns
   metadata and confirms membership.
2. Client connects to the collaboration server over `/collab/{documentId}`
   with its JWT; the server verifies membership with the backend.
3. Yjs updates flow peer-to-peer through the server (never full-document
   replacement). Awareness broadcasts cursors/selections.
4. The server persists binary updates and periodic snapshots to
   PostgreSQL. On reconnect, the client loads the snapshot + missed
   updates and converges via CRDT merge.

### Moving a Kanban task

1. Client drags a task; the UI updates optimistically with the task's
   `version`.
2. REST `PUT /api/v1/tasks/{id}` with the expected version; the backend
   applies optimistic locking — a stale version returns 409 and the client
   reloads the task.
3. After the commit, the backend publishes `TASK_UPDATED`; all connected
   clients apply it.

## Scaling notes

- The backend is stateless except for in-memory STOMP sessions; Redis
  Pub/Sub lets a second instance join without duplicate broadcasts.
- The collaboration server is the only stateful component (in-memory Yjs
  docs); it persists continuously so a restart reloads documents from
  PostgreSQL. Horizontal scaling of the sync service is out of scope for
  the MVP (see `docs/websocket-events.md`).
- A transactional outbox is a stretch feature for exactly-once-ish
  downstream effects; the MVP tolerates duplicate events by design.

## Local networking (two users, same trusted network)

- The host machine runs `docker compose up --build` and exposes Nginx on
  port 80. A second device on the same network opens
  `http://<host-machine-ip>/`. The frontend, API, STOMP, and Yjs sync all
  go through that one origin, so no CORS or mixed-content issues arise.
- PostgreSQL (5432) and Redis (6379) are bound to localhost only in
  development and must never be exposed to the public internet. Remote
  deployment requires HTTPS/WSS and a real `JWT_SECRET`.

## Failure handling

- Backend restart: durable state is in PostgreSQL; clients reconnect with
  backoff and recover from REST.
- Collaboration server restart: Yjs documents reload from persisted
  updates/snapshots; clients reconnect and re-sync.
- Redis loss: presence resets (users reappear on next heartbeat); Pub/Sub
  events in flight may be lost — clients recover durable state from REST.
- PostgreSQL loss: the backend fails health checks; no writes are accepted
  until it recovers (fail-closed, not silent loss).
