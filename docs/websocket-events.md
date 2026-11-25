# SyncSpace WebSocket Events

Two WebSocket systems exist. **STOMP over the Spring Boot backend** carries
workspace events (chat, tasks, presence, notifications). **The Yjs
collaboration server** carries document synchronization using the Yjs
protocol — never full-document replacement over STOMP.

## Connection

- STOMP endpoint: `ws://<host>/ws/` (through Nginx; direct backend
  `ws://localhost:8080/ws/`). Native WebSocket only — no SockJS fallback.
- Authentication: the STOMP `CONNECT` frame carries
  `Authorization: Bearer <access-token>` in its headers. The backend
  validates the JWT and binds the session to the user. Unauthenticated
  connections are rejected; expired tokens must be refreshed before
  reconnecting.
- Heartbeats: client and server send STOMP heartbeats every 10s. Three
  missed heartbeats mark the connection dead; the client reconnects with
  exponential backoff (1s, 2s, 4s, … capped at 30s, with jitter).
- Reconnect: after reconnecting, the client re-subscribes and recovers
  durable state from REST (message history, task list, notifications).
  Event replay is not guaranteed; PostgreSQL is the recovery source.

## Event envelope

Every STOMP message body is JSON with this envelope:

```json
{
  "eventId": "uuid",
  "eventType": "TASK_UPDATED",
  "workspaceId": "uuid",
  "resourceId": "uuid",
  "actorId": "uuid",
  "timestamp": "2026-11-24T06:00:00Z",
  "version": 1,
  "payload": {}
}
```

- `eventId` — unique per event; clients deduplicate on it.
- `eventType` — one of the types below.
- `workspaceId` — the workspace the event belongs to (null for
  user-targeted events).
- `resourceId` — the document / message / task / notification id.
- `actorId` — the user who caused the event.
- `version` — the resource version after the change (tasks/documents).
- Consumers must tolerate duplicate deliveries; exactly-once delivery is
  not claimed.

## Event types

| Event type | Payload | Meaning |
| --- | --- | --- |
| `MESSAGE_CREATED` | `{ channelId, message: { id, authorId, body, createdAt, clientMessageId } }` | New chat message (persisted first) |
| `MESSAGE_UPDATED` | `{ channelId, message }` | Author edited a message |
| `MESSAGE_DELETED` | `{ channelId, messageId }` | Message soft-deleted |
| `TYPING_STARTED` / `TYPING_STOPPED` | `{ channelId, userId, displayName }` | Typing indicator (ephemeral, not persisted) |
| `TASK_CREATED` | `{ task }` | New task |
| `TASK_UPDATED` | `{ task }` | Task edited/moved (payload carries new `version`) |
| `TASK_DELETED` | `{ taskId }` | Task removed |
| `PRESENCE_CHANGED` | `{ userId, status: "online" \| "offline", lastSeenAt }` | Presence update |
| `DOCUMENT_UPDATED` | `{ documentId, title, updatedAt, version }` | Document metadata changed (content sync is Yjs) |
| `NOTIFICATION_CREATED` | `{ notification }` | New notification for the user |
| `MEMBER_JOINED` / `MEMBER_LEFT` / `MEMBER_ROLE_CHANGED` | `{ userId, role? }` | Membership change |
| `WORKSPACE_UPDATED` | `{ workspace }` | Workspace renamed / edited |
| `ERROR` | `{ code, message }` | Sent to `/user/queue/errors` |

## Destinations

Application (client → server):

```text
/app/workspaces/{workspaceId}/chat        send a message (payload: { channelId, clientMessageId, body })
/app/workspaces/{workspaceId}/typing      typing indicator
/app/workspaces/{workspaceId}/presence    heartbeat / presence ping
```

Subscription (server → client):

```text
/topic/workspaces/{workspaceId}/messages
/topic/workspaces/{workspaceId}/tasks
/topic/workspaces/{workspaceId}/presence
/topic/workspaces/{workspaceId}/typing
/topic/workspaces/{workspaceId}/documents
/topic/workspaces/{workspaceId}/members
/user/queue/notifications
/user/queue/acknowledgements
/user/queue/errors
```

## Subscription authorization

Subscribing to `/topic/workspaces/{id}/…` requires membership of that
workspace. The backend checks membership on every `SUBSCRIBE` frame and
rejects unauthorized subscriptions with an `ERROR` frame to
`/user/queue/errors`. A user who is removed from a workspace is
unsubscribed server-side. `/user/queue/*` destinations are per-user and
require authentication only.

## Message acknowledgements

The server sends each `MESSAGE_CREATED` with a `messageId`. Clients
acknowledge receipt by sending to `/app/workspaces/{id}/ack` with
`{ messageId }`; the server records delivery for the sender's UI. Acks are
best-effort and never block delivery.

## Rate limiting and payload limits

- STOMP message size limit: 64 KB per frame (documents go through Yjs,
  not STOMP).
- Per-connection rate limit: 60 application messages per minute; exceeding
  it returns an `ERROR` frame (`RATE_LIMITED`) and the connection is
  throttled.
- Payloads are validated (Bean Validation on the server); invalid frames
  return `ERROR` (`VALIDATION_FAILED`) and are dropped.

## Yjs collaboration protocol

- Endpoint: `ws://<host>/collab/{documentId}` (through Nginx).
- Authentication: JWT in the connection query (`?token=…`) or
  `Authorization` header where the client supports it; the server verifies
  the token and document membership with the backend before accepting the
  connection. Unauthorized connections are closed with code 4401.
- Protocol: standard `y-websocket` sync + awareness. The server relays
  binary Yjs updates between clients, broadcasts awareness state
  (cursor/selection, user color), and persists updates and snapshots to
  PostgreSQL. On reconnect, the client requests the state vector and
  receives only the updates it missed.
- Saving states shown in the editor: `saving` (local update not yet
  acknowledged), `saved` (server persisted), `offline` (no connection —
  edits queue in IndexedDB), `reconnecting`.
