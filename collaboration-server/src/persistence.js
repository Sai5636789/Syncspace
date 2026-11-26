/**
 * PostgreSQL persistence for Yjs document state.
 *
 * Tables (Flyway V3, backend/src/main/resources/db/migration/):
 *  - document_updates: append-only log, one row per received binary
 *    update (BIGINT identity id, document_id, update_data BYTEA,
 *    created_by_id, created_at). Rows are never updated or deleted
 *    except by cascade when the document is deleted.
 *  - document_snapshots: full encoded state plus the state vector at
 *    snapshot time, newest first.
 *
 * Snapshot policy (also documented in collab.js): a snapshot is written
 * after N persisted updates, at most once per interval for dirty
 * documents, and when the last client leaves a document. Loading applies
 * the latest snapshot and then *all* logged updates in id order —
 * re-applying updates already covered by the snapshot is harmless
 * because Yjs update application is idempotent, so no bookkeeping of
 * "which updates a snapshot covers" is needed.
 *
 * If the database is unreachable, persistence disables itself (with a
 * warning) instead of taking sync down: live collaboration keeps
 * working in memory, and a restart would lose unpersisted state. The
 * health endpoint reports whether persistence is up.
 */
import pg from 'pg';

export class PostgresPersistence {
  #pool;
  #disabled = false;

  constructor(databaseUrl) {
    this.#pool = new pg.Pool({ connectionString: databaseUrl, max: 5 });
    this.#pool.on('error', (err) => {
      console.warn(`[persistence] pool error (persistence disabled): ${err.message}`);
      this.#disabled = true;
    });
  }

  get disabled() {
    return this.#disabled;
  }

  /** One cheap probe, used by /health and by the test suite. */
  async ping() {
    if (this.#disabled) return false;
    try {
      await this.#pool.query('SELECT 1');
      return true;
    } catch (err) {
      console.warn(`[persistence] ping failed (persistence disabled): ${err.message}`);
      this.#disabled = true;
      return false;
    }
  }

  /**
   * Loads a document's persisted state: the latest snapshot (if any)
   * followed by every logged update, oldest first. Returns
   * `{ snapshot: Buffer | null, updates: Buffer[] }`.
   */
  async load(documentId) {
    const snapshotResult = await this.#pool.query(
      `SELECT snapshot_data FROM document_snapshots
        WHERE document_id = $1 ORDER BY created_at DESC LIMIT 1`,
      [documentId],
    );
    const updatesResult = await this.#pool.query(
      `SELECT update_data FROM document_updates
        WHERE document_id = $1 ORDER BY id ASC`,
      [documentId],
    );
    return {
      snapshot: snapshotResult.rows[0]?.snapshot_data ?? null,
      updates: updatesResult.rows.map((row) => row.update_data),
    };
  }

  /** Appends one received binary update to the log. */
  async saveUpdate(documentId, update, userId) {
    await this.#pool.query(
      `INSERT INTO document_updates (document_id, update_data, created_by_id)
       VALUES ($1, $2, $3)`,
      [documentId, Buffer.from(update), userId ?? null],
    );
  }

  /** Writes a full-state snapshot row. */
  async saveSnapshot(documentId, state, stateVector) {
    await this.#pool.query(
      `INSERT INTO document_snapshots (document_id, snapshot_data, state_vector)
       VALUES ($1, $2, $3)`,
      [documentId, Buffer.from(state), Buffer.from(stateVector)],
    );
  }

  async close() {
    await this.#pool.end().catch(() => {});
  }
}
