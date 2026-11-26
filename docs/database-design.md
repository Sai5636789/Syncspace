# SyncSpace Database Design

PostgreSQL 16 is the durable source of truth. All changes go through
Flyway migrations (`backend/src/main/resources/db/migration/`).
Conventions: UUID primary keys (`gen_random_uuid()`), `created_at` /
`updated_at` timestamps on every table, foreign keys with explicit
`ON DELETE` behavior, unique constraints where the domain requires them,
and an optimistic-locking `version` column on tables with concurrent
writes (tasks, documents).

## Entity relationship summary

```
users 1───* workspace_members *───1 workspaces
users 1───* refresh_tokens
workspaces 1───* workspace_invitations
workspaces 1───* documents 1───* document_updates, document_snapshots
workspaces 1───* channels 1───* messages
workspaces 1───* tasks 1───* task_activity
users 1───* notifications
users 1───* audit_logs
workspaces 1───* outbox_events (stretch)
```

Cross-workspace references are prevented by including `workspace_id` on
every workspace-owned row and by checking membership in the service layer
and in SQL where it matters.

## Tables

### users

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| email | citext, UNIQUE, NOT NULL | login identifier; case-insensitive |
| display_name | VARCHAR(100), NOT NULL | |
| password_hash | VARCHAR(255), NOT NULL | BCrypt |
| avatar_url | VARCHAR(512) | nullable |
| created_at / updated_at | TIMESTAMPTZ, NOT NULL | |

Indexes: `users(email)` (unique).

### refresh_tokens

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| user_id | UUID FK → users, ON DELETE CASCADE | |
| token_hash | VARCHAR(255), UNIQUE, NOT NULL | SHA-256 of the raw token; raw token never stored |
| expires_at | TIMESTAMPTZ, NOT NULL | |
| revoked_at | TIMESTAMPTZ | set on rotation / logout |
| replaced_by_token_id | UUID FK → refresh_tokens | rotation chain |
| created_at | TIMESTAMPTZ, NOT NULL | |

Indexes: `refresh_tokens(token_hash)`, `refresh_tokens(user_id, expires_at)`.

### workspaces

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| name | VARCHAR(120), NOT NULL | |
| description | TEXT | |
| owner_id | UUID FK → users, ON DELETE RESTRICT | |
| created_at / updated_at | TIMESTAMPTZ, NOT NULL | |

### workspace_members

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| workspace_id | UUID FK → workspaces, ON DELETE CASCADE | |
| user_id | UUID FK → users, ON DELETE CASCADE | |
| role | VARCHAR(20), NOT NULL | OWNER / ADMIN / MEMBER / VIEWER |
| joined_at | TIMESTAMPTZ, NOT NULL | |
| created_at / updated_at | TIMESTAMPTZ, NOT NULL | |

Constraints: `UNIQUE(workspace_id, user_id)`; `CHECK (role IN
('OWNER','ADMIN','MEMBER','VIEWER'))`.
Indexes: `workspace_members(workspace_id)`, `workspace_members(user_id)`.

### workspace_invitations

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| workspace_id | UUID FK → workspaces, ON DELETE CASCADE | |
| invited_by_id | UUID FK → users, ON DELETE SET NULL | |
| email | citext, NOT NULL | invited address |
| token_hash | VARCHAR(255), UNIQUE, NOT NULL | |
| role | VARCHAR(20), NOT NULL | role granted on accept |
| expires_at | TIMESTAMPTZ, NOT NULL | |
| accepted_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ, NOT NULL | |

Indexes: `workspace_invitations(token_hash)`,
`workspace_invitations(workspace_id, email)`.

### documents

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| workspace_id | UUID FK → workspaces, ON DELETE CASCADE | |
| title | VARCHAR(200), NOT NULL | |
| created_by_id | UUID FK → users, ON DELETE SET NULL | |
| version | BIGINT, NOT NULL, DEFAULT 0 | optimistic locking |
| updated_at / created_at | TIMESTAMPTZ, NOT NULL | |

Indexes: `documents(workspace_id, updated_at DESC)`.
Note: document *content* is Yjs binary state, persisted as updates and
snapshots (below), not as HTML.

### document_updates

| Column | Type | Notes |
| --- | --- | --- |
| id | BIGINT GENERATED ALWAYS AS IDENTITY PK | append-only log |
| document_id | UUID FK → documents, ON DELETE CASCADE | |
| update_data | BYTEA, NOT NULL | Yjs binary update |
| created_by_id | UUID FK → users, ON DELETE SET NULL | |
| created_at | TIMESTAMPTZ, NOT NULL | |

Indexes: `document_updates(document_id, id)`.

### document_snapshots

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| document_id | UUID FK → documents, ON DELETE CASCADE | |
| snapshot_data | BYTEA, NOT NULL | Yjs encoded state |
| state_vector | BYTEA | vector at snapshot time |
| created_at | TIMESTAMPTZ, NOT NULL | |

Indexes: `document_snapshots(document_id, created_at DESC)`.

### channels

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| workspace_id | UUID FK → workspaces, ON DELETE CASCADE | |
| name | VARCHAR(80), NOT NULL | |
| description | TEXT | |
| created_by_id | UUID FK → users, ON DELETE SET NULL | |
| created_at / updated_at | TIMESTAMPTZ, NOT NULL | |

Constraints: `UNIQUE(workspace_id, name)`.
Indexes: `channels(workspace_id)`.

### messages

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| channel_id | UUID FK → channels, ON DELETE CASCADE | |
| workspace_id | UUID FK → workspaces, ON DELETE CASCADE | denormalized for isolation checks |
| author_id | UUID FK → users, ON DELETE SET NULL | |
| client_message_id | UUID, NOT NULL | idempotency key from the client |
| body | TEXT, NOT NULL | |
| edited_at | TIMESTAMPTZ | |
| deleted_at | TIMESTAMPTZ | soft delete |
| created_at / updated_at | TIMESTAMPTZ, NOT NULL | |

Constraints: `UNIQUE(channel_id, client_message_id)` (duplicate prevention).
Indexes: `messages(channel_id, created_at DESC)`,
`messages(workspace_id, created_at DESC)`.

### channel_reads

| Column | Type | Notes |
| --- | --- | --- |
| user_id | UUID FK → users, ON DELETE CASCADE | part of the PK |
| channel_id | UUID FK → channels, ON DELETE CASCADE | part of the PK |
| last_read_at | TIMESTAMPTZ, NOT NULL | instant the user last marked the channel read |

PK: `(user_id, channel_id)`. No `created_at`/`updated_at`: the row is a
read pointer, `last_read_at` is its state. Unread count for a user is the
number of non-deleted messages in the channel created after
`last_read_at` and not authored by that user (all of them, when no row
exists).

### tasks

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| workspace_id | UUID FK → workspaces, ON DELETE CASCADE | |
| title | VARCHAR(200), NOT NULL | |
| description | TEXT | |
| status | VARCHAR(20), NOT NULL | TODO / IN_PROGRESS / IN_REVIEW / COMPLETED |
| priority | VARCHAR(20), NOT NULL | LOW / MEDIUM / HIGH / URGENT |
| assignee_id | UUID FK → users, ON DELETE SET NULL | |
| created_by_id | UUID FK → users, ON DELETE SET NULL | |
| due_date | DATE | |
| position | DOUBLE PRECISION, NOT NULL | fractional ordering within a column |
| version | BIGINT, NOT NULL, DEFAULT 0 | optimistic locking |
| created_at / updated_at | TIMESTAMPTZ, NOT NULL | |

Indexes: `tasks(workspace_id, status, position)`,
`tasks(assignee_id, status)`.

### task_activity

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| task_id | UUID FK → tasks, ON DELETE CASCADE | |
| actor_id | UUID FK → users, ON DELETE SET NULL | |
| action | VARCHAR(40), NOT NULL | e.g. CREATED, MOVED, ASSIGNED, STATUS_CHANGED |
| details | JSONB | |
| created_at | TIMESTAMPTZ, NOT NULL | |

Indexes: `task_activity(task_id, created_at DESC)`.

### notifications

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| user_id | UUID FK → users, ON DELETE CASCADE | recipient |
| workspace_id | UUID FK → workspaces, ON DELETE CASCADE | |
| type | VARCHAR(40), NOT NULL | INVITATION / TASK_ASSIGNED / TASK_STATUS / MENTION / DOCUMENT_SHARED |
| title | VARCHAR(200), NOT NULL | |
| body | TEXT | |
| resource_id | UUID | linked document/task/message |
| read_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ, NOT NULL | |

Indexes: `notifications(user_id, read_at, created_at DESC)` (unread counts).

### audit_logs

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| user_id | UUID FK → users, ON DELETE SET NULL | |
| workspace_id | UUID FK → workspaces, ON DELETE CASCADE | |
| action | VARCHAR(60), NOT NULL | |
| resource_type | VARCHAR(40) | |
| resource_id | UUID | |
| details | JSONB | |
| created_at | TIMESTAMPTZ, NOT NULL | |

Indexes: `audit_logs(workspace_id, created_at DESC)`.

### outbox_events (stretch)

| Column | Type | Notes |
| --- | --- | --- |
| id | UUID PK | |
| aggregate_type | VARCHAR(40), NOT NULL | |
| aggregate_id | UUID, NOT NULL | |
| event_type | VARCHAR(60), NOT NULL | |
| payload | JSONB, NOT NULL | |
| published_at | TIMESTAMPTZ | |
| created_at | TIMESTAMPTZ, NOT NULL | |

Written in the same transaction as the domain change; a relay publishes
to Redis and marks `published_at`.

## Migrations

- `V1__foundation.sql` — users, refresh_tokens, workspaces,
  workspace_members, workspace_invitations.
- `V2__messaging.sql` — channels, messages, channel_reads. (The original
  plan listed V2 as documents; documents are not built yet, so messaging
  took V2 and documents will take a later number.)
- `V3__documents.sql` — documents, document_updates, document_snapshots
  (landed with the documents slice; content sync is Yjs, see below).
- `V4__tasks.sql` — tasks, task_activity.
- `V5__notifications_audit.sql` — notifications, audit_logs.
- `V6__outbox.sql` — outbox_events (stretch, only when implemented).
