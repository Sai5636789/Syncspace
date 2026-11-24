# SyncSpace API Contracts

Base URL (through Nginx): `/api/v1`. Direct backend: `http://localhost:8080/api/v1`.
OpenAPI/Swagger UI: `/swagger-ui.html` (backend, dev profile).
Auth: `Authorization: Bearer <access-token>` unless noted. All responses
are JSON. Errors use the envelope `{ "error": { "code", "message",
"details"? } }` with the matching HTTP status.

Common codes: `400` validation, `401` unauthenticated, `403` forbidden
(not a member / insufficient role), `404` not found, `409` conflict
(optimistic-locking version mismatch, duplicate), `429` rate limited.

## Auth

### POST /auth/register

```json
// Request
{ "email": "sai@example.com", "displayName": "Sai", "password": "…" }
// 201 Response
{ "user": { "id": "uuid", "email": "sai@example.com", "displayName": "Sai" },
  "accessToken": "…", "refreshToken": "…", "expiresInSeconds": 900 }
```

Password: 8–128 chars. Email must be unique (409 on duplicate).

### POST /auth/login

```json
// Request
{ "email": "sai@example.com", "password": "…" }
// 200 Response — same shape as register
```

### POST /auth/refresh

```json
// Request
{ "refreshToken": "…" }
// 200 Response
{ "accessToken": "…", "refreshToken": "…", "expiresInSeconds": 900 }
```

Rotation: the presented refresh token is revoked and replaced. Reusing a
revoked token revokes the whole token family (possible theft) and returns
401.

### POST /auth/logout

Auth required. Body `{ "refreshToken": "…" }` — revokes that token.
`204 No Content`.

### GET /auth/me

Auth required. Returns the current user. `200`.

## Users

### GET /users/me — same as `/auth/me`.

### PATCH /users/me

Auth required. `{ "displayName": "…", "avatarUrl": "…" }`. `200` user.

### GET /users/search?q=…

Auth required. Finds registered users by email/display name (for invites).
`200` `{ "users": [ { "id", "email", "displayName", "avatarUrl" } ] }`.

## Workspaces

All workspace routes require membership unless noted; role requirements
are listed per route.

### GET /workspaces

Auth required. Workspaces the user belongs to.
`200` `{ "workspaces": [ { "id", "name", "description", "role", "memberCount", "createdAt" } ] }`.

### POST /workspaces

Auth required. `{ "name": "…", "description": "…" }`.
Creates the workspace; creator becomes OWNER and a member.
`201` workspace (with `role: "OWNER"`).

### GET /workspaces/{workspaceId}

Requires membership (any role). `200` workspace.

### PATCH /workspaces/{workspaceId}

Requires OWNER or ADMIN. `{ "name", "description" }`. `200` workspace.

### DELETE /workspaces/{workspaceId}

Requires OWNER. `204 No Content`. Deletes the workspace and its members,
invitations, documents, channels, messages, tasks (cascade).

### POST /workspaces/{workspaceId}/leave

Requires membership; OWNER cannot leave while other members exist (transfer
ownership first — `409`). `204 No Content`.

### GET /workspaces/{workspaceId}/members

Requires membership. `200` `{ "members": [ { "userId", "email",
"displayName", "role", "joinedAt" } ] }`.

### PATCH /workspaces/{workspaceId}/members/{userId}

Requires OWNER or ADMIN. `{ "role": "ADMIN" | "MEMBER" | "VIEWER" }`.
OWNER role cannot be granted this way. `200` member.

### DELETE /workspaces/{workspaceId}/members/{userId}

Requires OWNER or ADMIN. Cannot remove the OWNER or yourself. `204`.

### POST /workspaces/{workspaceId}/invitations

Requires OWNER or ADMIN. `{ "email": "…", "role": "MEMBER",
"expiresInHours": 72 }`. Creates an expiring invitation and (for a
registered user) a notification. `201` `{ "invitation": { "id", "email",
"role", "expiresAt" }, "inviteUrl": "…/join?token=…" }`. The raw token is
returned once, at creation; only its hash is stored.

### GET /workspaces/{workspaceId}/invitations

Requires OWNER or ADMIN. Lists pending invitations. `200`.

### POST /invitations/accept

Auth required. `{ "token": "…" }`. Validates the token, expiry, and that
the caller's email matches the invitation (or the invitation is open).
Adds the caller as a member with the invited role. `200` workspace.
`410` if expired, `404` if unknown.

## Documents (metadata; content sync is Yjs — see websocket-events.md)

### GET /workspaces/{workspaceId}/documents

Requires membership. `200` `{ "documents": [ { "id", "title",
"createdBy", "updatedAt" } ] }`.

### POST /workspaces/{workspaceId}/documents

Requires MEMBER or above. `{ "title": "…" }`. `201` document.

### GET /documents/{documentId}

Requires membership of the owning workspace. `200` document + `workspaceId`.

### PATCH /documents/{documentId}

Requires MEMBER or above. `{ "title": "…" }`. `200` document.

### DELETE /documents/{documentId}

Requires MEMBER or above. `204`. Deletes metadata, updates, snapshots.

## Channels and messages (implemented with the messaging slice)

### GET /workspaces/{workspaceId}/channels — membership required.
### POST /workspaces/{workspaceId}/channels — MEMBER or above.
### GET /channels/{channelId}/messages?before=<cursor>&limit=50 — membership required, cursor-paginated.
### PATCH /messages/{messageId} — author only, within edit window.
### DELETE /messages/{messageId} — author or ADMIN+; soft delete.

## Tasks (implemented with the Kanban slice)

### GET /workspaces/{workspaceId}/tasks — membership required.
### POST /workspaces/{workspaceId}/tasks — MEMBER or above.
### PUT /tasks/{taskId} — MEMBER or above; body includes `version`; stale version → `409`.
### DELETE /tasks/{taskId} — MEMBER or above (creator or ADMIN+).

## Notifications

### GET /notifications?unreadOnly=true — auth required.
### POST /notifications/{id}/read — auth required.
### POST /notifications/read-all — auth required.

## Health and operations

- `GET /actuator/health` — liveness/readiness (no auth).
- `GET /actuator/info` — build info (no auth).
- `GET /api/v1/health` — `{ "status": "ok", "service": "syncspace-backend" }`.
