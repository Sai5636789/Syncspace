-- V2__messaging.sql — SyncSpace channels, messages, channel read pointers.
-- Conventions: UUID primary keys, created_at/updated_at timestamps,
-- foreign keys with explicit ON DELETE behavior, unique constraints.
-- Note: docs/database-design.md listed V2 as documents; documents are not
-- built yet, so messaging takes V2 and documents will take a later number.

-- ---------------------------------------------------------------- channels
CREATE TABLE channels (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name          VARCHAR(80) NOT NULL,
    description   TEXT,
    created_by_id UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_channel_workspace_name UNIQUE (workspace_id, name)
);
CREATE INDEX idx_channels_workspace ON channels (workspace_id);

-- ---------------------------------------------------------------- messages
CREATE TABLE messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id        UUID NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    workspace_id      UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    author_id         UUID REFERENCES users (id) ON DELETE SET NULL,
    client_message_id UUID NOT NULL,
    body              TEXT NOT NULL,
    edited_at         TIMESTAMPTZ,
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_message_channel_client_id UNIQUE (channel_id, client_message_id)
);
CREATE INDEX idx_messages_channel_created ON messages (channel_id, created_at DESC);
CREATE INDEX idx_messages_workspace_created ON messages (workspace_id, created_at DESC);

-- ------------------------------------------------------------ channel_reads
-- Per-user read pointer per channel; drives the unread count. No
-- created_at/updated_at: the row is a pointer, last_read_at is its state.
CREATE TABLE channel_reads (
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel_id  UUID NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    last_read_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, channel_id)
);
