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
revoked token returns 401. (Whole-family revocation on detected reuse is a
future hardening step, not implemented.)

### POST /auth/logout

Auth required. Body `{ "refreshToken": "…" }` — revokes that token.
`204 No Content`.

### GET /auth/me

Auth required. Returns the current user. `200`.

## Users

### GET /users/me — same as `/auth/me`. (Not built; use `GET /auth/me`.)

### PATCH /users/me — planned, not built yet

Auth required. `{ "displayName": "…", "avatarUrl": "…" }`. `200` user.

### GET /users/search?q=… — planned, not built yet

Auth required. Finds registered users by email/display name (for invites).
`200` `{ "users": [ { "id", "email", "displayName", "avatarUrl" } ] }`.

## Workspaces

All workspace routes require membership unless noted; role requirements
are listed per route.

### GET /workspaces

Auth required. Workspaces the user belongs to.
`200` a JSON array of `{ "id", "name", "description", "ownerId", "role",
"createdAt", "updatedAt" }`. (The `role` is the caller's role.)

### POST /workspaces

Auth required. `{ "name": "…", "description": "…" }`.
Creates the workspace; creator becomes OWNER and a member.
`201` workspace (with `role: "OWNER"`).

### GET /workspaces/{workspaceId}

Requires membership (any role). `200` workspace.

### PATCH /workspaces/{workspaceId}

Requires OWNER or ADMIN. `{ "name", "description" }`. `200` workspace.

### DELETE /workspaces/{workspaceId}

Requires OWNER. `204 No Content`. Deletes the workspace and its members
and invitations (cascade).

### POST /workspaces/{workspaceId}/leave

Requires membership; OWNER cannot leave (transfer ownership first, or
delete the workspace — `409`). `204 No Content`.

### POST /workspaces/{workspaceId}/transfer

Requires OWNER. `{ "newOwnerId": "…" }` — the new owner must already be a
member. The old owner becomes ADMIN, the new owner becomes OWNER.
`200` workspace (with the caller's new role, `ADMIN`).

### GET /workspaces/{workspaceId}/members

Requires membership. `200` a JSON array of `{ "userId", "email",
"displayName", "role", "joinedAt" }`.

### PATCH /workspaces/{workspaceId}/members/{userId} — planned, not built yet

Requires OWNER or ADMIN. `{ "role": "ADMIN" | "MEMBER" | "VIEWER" }`.
OWNER role cannot be granted this way. `200` member.

### DELETE /workspaces/{workspaceId}/members/{userId}

Requires OWNER or ADMIN. Cannot remove the OWNER or yourself (use leave).
An admin cannot remove another admin. `204`.

### POST /workspaces/{workspaceId}/invitations

Requires OWNER or ADMIN. `{ "email": "…", "role": "ADMIN" | "MEMBER" |
"VIEWER" }` — OWNER cannot be granted by invitation. Invitations expire
after 72h (configurable). `201` `{ "id", "workspaceId", "email", "role",
"expiresAt", "token" }`. The raw token is returned once, at creation; only
its SHA-256 hash is stored. (No email is sent yet — the inviter shares the
link; email delivery lands with notifications.)

### GET /workspaces/{workspaceId}/invitations — planned, not built yet

Requires OWNER or ADMIN. Lists pending invitations. `200`.

### POST /workspaces/invitations/{token}/accept

Auth required. Validates the token, expiry, and that the caller's email
matches the invitation. Adds the caller as a member with the invited role.
`200` workspace. `401` if the token is unknown, `409` if expired, already
used, or the caller is already a member, `403` if the invitation was sent
to a different email address.

## Documents (implemented with the documents slice; metadata only, content sync is Yjs — see websocket-events.md)

### GET /workspaces/{workspaceId}/documents

Requires membership. `200` `{ "documents": [ { "id", "workspaceId",
"title", "createdBy", "version", "createdAt", "updatedAt" } ] }`.

### POST /workspaces/{workspaceId}/documents

Requires MEMBER or above. `{ "title": "…" }` (max 200 chars, blank
rejected with `400`). `201` document. Non-members get `404`
(workspace existence is not leaked); VIEWER gets `403`.

### GET /documents/{documentId}

Requires membership of the owning workspace. `200` document +
`workspaceId`. A document from another workspace is `404` for the
caller, even if they belong to that other workspace.

### PATCH /documents/{documentId}

Requires MEMBER or above. `{ "title": "…" }`. `200` document with the
incremented optimistic-locking `version`. A `DOCUMENT_UPDATED` event is
broadcast to `/topic/workspaces/{workspaceId}/documents` after the
commit. VIEWER gets `403`.

### DELETE /documents/{documentId}

Requires MEMBER or above. `204`. Deletes metadata, updates, snapshots
(database cascade).

## Channels and messages (implemented with the messaging slice)

### GET /workspaces/{workspaceId}/channels — membership required.
`200` a JSON array of `{ "id", "workspaceId", "name", "description",
"createdById", "unreadCount", "createdAt", "updatedAt" }`. `unreadCount`
is the caller's unread messages in that channel: messages created after
the caller's last read, excluding the caller's own messages; with no read
pointer it counts all messages not authored by the caller.

### POST /workspaces/{workspaceId}/channels — MEMBER or above.
`{ "name": "…" (max 80 chars), "description": "…" (max 2000 chars) }`.
`201` channel. Duplicate name in the workspace → `409`; blank name →
`400`. Every workspace is created with a default `general` channel.
Non-members get `404` on channel routes (workspace existence is not
leaked).

### POST /channels/{channelId}/read — membership required.
Marks the channel read for the caller. `200`
`{ "channelId": "…", "lastReadAt": "…" }`.

### GET /channels/{channelId}/messages?page=0&size=50 — membership required.
Page/size paginated, chronological (page 0 holds the oldest messages).
`200` `{ "content": [ { "id", "channelId", "workspaceId", "authorId",
"authorDisplayName", "clientMessageId", "body", "editedAt", "deletedAt",
"createdAt", "updatedAt" } ], "page", "size", "totalElements",
"totalPages" }`. Default size 50, maximum 100. Soft-deleted messages are
excluded from history entirely.

### PATCH /messages/{messageId} — author only, `{ "body": "…" }` (max
10000 chars, blank rejected with `400`). Sets `editedAt`. `200` message.
`403` for a non-author, `404` for an unknown message id.

### DELETE /messages/{messageId} — author or ADMIN+. Soft delete.
`204`. A MESSAGE_DELETED event is broadcast. `403` for a non-author
below ADMIN, `404` for an unknown message id.

### STOMP chat
- Send: `/app/workspaces/{workspaceId}/chat` with
  `{ channelId, clientMessageId, body }`. The sender gets a MESSAGE_SENT
  envelope on `/user/queue/acknowledgements`
  (`payload: { status: "SENT" | "DUPLICATE", message }`); a duplicate
  `clientMessageId` returns the existing message with no second broadcast.
  The MESSAGE_CREATED broadcast goes to
  `/topic/workspaces/{workspaceId}/messages` after the DB commit.
- Typing: `/app/workspaces/{workspaceId}/typing` with
  `{ channelId, typing: true | false }` → TYPING_STARTED / TYPING_STOPPED
  on `/topic/workspaces/{workspaceId}/typing` (ephemeral, not persisted).
- Acks: `/app/workspaces/{workspaceId}/ack` with `{ messageId }` →
  a MESSAGE_ACKNOWLEDGED envelope (`payload: { messageId, status:
  "ACKED" }`) on `/user/queue/acknowledgements` (best-effort, not
  persisted).

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
