-- V3__documents.sql — SyncSpace documents, Yjs update log, snapshots.
-- Conventions: UUID primary keys, created_at/updated_at timestamps,
-- foreign keys with explicit ON DELETE behavior, unique constraints.
-- Document *content* is Yjs binary state, persisted as updates and
-- snapshots below, never as HTML. documents.version is the optimistic
-- locking counter for metadata writes (rename); content merges via CRDT.

-- ---------------------------------------------------------------- documents
CREATE TABLE documents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    title         VARCHAR(200) NOT NULL,
    created_by_id UUID REFERENCES users (id) ON DELETE SET NULL,
    version       BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_documents_workspace_updated ON documents (workspace_id, updated_at DESC);

-- ----------------------------------------------------------- document_updates
-- Append-only log of Yjs binary updates, in arrival order. The
-- collaboration server writes one row per received update and never
-- updates or deletes rows (except by cascade when the document goes).
CREATE TABLE document_updates (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id   UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    update_data   BYTEA NOT NULL,
    created_by_id UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_updates_document ON document_updates (document_id, id);

-- --------------------------------------------------------- document_snapshots
-- Periodic full-state snapshots so a reconnecting client loads the latest
-- snapshot plus only the updates after it, instead of the whole log.
CREATE TABLE document_snapshots (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    snapshot_data BYTEA NOT NULL,
    state_vector  BYTEA,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_snapshots_document_created ON document_snapshots (document_id, created_at DESC);
