/**
 * Yjs document rooms: sync protocol, awareness relay, persistence.
 *
 * Protocol (the standard y-websocket framing, implemented here directly
 * on y-protocols because y-websocket 3.x no longer ships server utils):
 * every WebSocket frame is `[messageType: varUint, ...payload]` where
 * messageType is 0 = sync, 1 = awareness, 2 = auth, 3 = query-awareness.
 * Sync payloads follow y-protocols/sync (SyncStep1 / SyncStep2 /
 * Update). Updates are relayed between clients — never whole-document
 * replacement — and awareness state (cursors, selections, user colors)
 * is broadcast to the other clients in the room.
 *
 * Persistence (see persistence.js): every update a client sends is
 * appended to `document_updates` and the room is marked dirty. A
 * snapshot is written to `document_snapshots` after
 * `snapshotEveryUpdates` persisted updates, at most once per
 * `snapshotIntervalMs` for dirty rooms, and when the last client leaves
 * a room. On (re)connect the room loads the latest snapshot plus all
 * logged updates, so a client that reconnects receives only what it
 * missed (its SyncStep1 state vector drives the SyncStep2 reply) and
 * converges without a full re-download.
 *
 * Rooms live in memory only while at least one client is connected; the
 * last disconnect snapshots (if dirty) and drops the room, so a restart
 * reloads everything from PostgreSQL.
 */
import * as Y from 'yjs';
import { WebSocket } from 'ws';
import * as syncProtocol from 'y-protocols/sync';
import * as awarenessProtocol from 'y-protocols/awareness';
import * as encoding from 'lib0/encoding';
import * as decoding from 'lib0/decoding';

export const messageSync = 0;
export const messageAwareness = 1;
export const messageAuth = 2;
export const messageQueryAwareness = 3;

export class RoomManager {
  #rooms = new Map();
  #persistence;
  #snapshotEveryUpdates;
  #snapshotIntervalMs;
  #snapshotTimer;

  constructor(persistence, { snapshotEveryUpdates, snapshotIntervalMs }) {
    this.#persistence = persistence;
    this.#snapshotEveryUpdates = snapshotEveryUpdates;
    this.#snapshotIntervalMs = snapshotIntervalMs;
    this.#snapshotTimer = setInterval(() => {
      void this.snapshotDirtyRooms();
    }, snapshotIntervalMs);
    this.#snapshotTimer.unref?.();
  }

  get roomCount() {
    return this.#rooms.size;
  }

  get connectionCount() {
    let count = 0;
    for (const room of this.#rooms.values()) count += room.conns.size;
    return count;
  }

  /** Loads (or reuses) the room for a document, restoring persisted state. */
  async getOrCreateRoom(documentId) {
    const existing = this.#rooms.get(documentId);
    if (existing) return existing;

    const doc = new Y.Doc();
    const room = {
      documentId,
      doc,
      awareness: new awarenessProtocol.Awareness(doc),
      conns: new Set(),
      updatesSinceSnapshot: 0,
      dirty: false,
    };
    // Broadcast + persist updates that arrive from a connected client.
    // The transaction origin is the connection object (see
    // handleMessage), so locally applied state (load, snapshot) — origin
    // null — is never re-persisted or re-broadcast.
    doc.on('update', (update, origin) => {
      if (origin == null || typeof origin !== 'object' || !room.conns.has(origin)) return;
      void this.#onClientUpdate(room, origin, update);
    });
    this.#rooms.set(documentId, room);

    if (!this.#persistence.disabled) {
      try {
        const { snapshot, updates } = await this.#persistence.load(documentId);
        if (snapshot) Y.applyUpdate(doc, new Uint8Array(snapshot));
        for (const update of updates) Y.applyUpdate(doc, new Uint8Array(update));
      } catch (err) {
        console.warn(`[collab] failed to load persisted state for ${documentId}: ${err.message}`);
      }
    }
    return room;
  }

  /**
   * Attaches an authorized connection to its room: replays any messages
   * queued while authorization was in flight, sends the server's
   * SyncStep1 (the client answers with SyncStep2) and the current
   * awareness state, then wires up message/close handling.
   */
  async addConnection(room, ws, userId, pendingMessages = []) {
    const conn = { ws, userId, room, clientIds: new Set() };
    room.conns.add(conn);

    ws.on('message', (data) => this.#handleMessage(room, conn, data));
    ws.on('close', () => void this.#removeConnection(room, conn));
    ws.on('error', () => void this.#removeConnection(room, conn));
    for (const data of pendingMessages) this.#handleMessage(room, conn, data);

    const syncEncoder = encoding.createEncoder();
    encoding.writeVarUint(syncEncoder, messageSync);
    syncProtocol.writeSyncStep1(syncEncoder, room.doc);
    this.#send(ws, encoding.toUint8Array(syncEncoder));

    const awarenessEncoder = encoding.createEncoder();
    encoding.writeVarUint(awarenessEncoder, messageAwareness);
    encoding.writeVarUint8Array(
      awarenessEncoder,
      awarenessProtocol.encodeAwarenessUpdate(
        room.awareness,
        Array.from(room.awareness.getStates().keys()),
      ),
    );
    this.#send(ws, encoding.toUint8Array(awarenessEncoder));

    return conn;
  }

  /** Writes snapshots for every dirty room (periodic timer). */
  async snapshotDirtyRooms() {
    for (const room of [...this.#rooms.values()]) {
      if (room.dirty) await this.#writeSnapshot(room);
    }
  }

  async close() {
    clearInterval(this.#snapshotTimer);
    await this.snapshotDirtyRooms();
    for (const room of this.#rooms.values()) {
      for (const conn of room.conns) conn.ws.close();
      room.awareness.destroy();
      room.doc.destroy();
    }
    this.#rooms.clear();
  }

  // -- internals -----------------------------------------------------------

  async #onClientUpdate(room, originConn, update) {
    // Relay to every other client in the room first: sync latency must
    // not wait on the database.
    const encoder = encoding.createEncoder();
    encoding.writeVarUint(encoder, messageSync);
    syncProtocol.writeUpdate(encoder, update);
    const message = encoding.toUint8Array(encoder);
    for (const conn of room.conns) {
      if (conn !== originConn) this.#send(conn.ws, message);
    }
    // Then persist (append-only log) and snapshot when due.
    if (this.#persistence.disabled) return;
    try {
      await this.#persistence.saveUpdate(room.documentId, update, originConn.userId);
      room.updatesSinceSnapshot += 1;
      room.dirty = true;
      if (room.updatesSinceSnapshot >= this.#snapshotEveryUpdates) {
        await this.#writeSnapshot(room);
      }
    } catch (err) {
      console.warn(`[collab] failed to persist update for ${room.documentId}: ${err.message}`);
    }
  }

  async #writeSnapshot(room) {
    if (this.#persistence.disabled) return;
    try {
      await this.#persistence.saveSnapshot(
        room.documentId,
        Y.encodeStateAsUpdate(room.doc),
        Y.encodeStateVector(room.doc),
      );
      room.updatesSinceSnapshot = 0;
      room.dirty = false;
    } catch (err) {
      console.warn(`[collab] failed to write snapshot for ${room.documentId}: ${err.message}`);
    }
  }

  #handleMessage(room, conn, data) {
    const buffer = Buffer.isBuffer(data) ? data : Buffer.from(data);
    const decoder = decoding.createDecoder(new Uint8Array(buffer));
    const messageType = decoding.readVarUint(decoder);
    switch (messageType) {
      case messageSync: {
        const encoder = encoding.createEncoder();
        // The outer messageSync prefix goes on first; readSyncMessage
        // appends the sync reply (SyncStep2) after it. Without the
        // prefix the client would read the reply's sync-type byte as
        // the outer message type and misparse the frame.
        encoding.writeVarUint(encoder, messageSync);
        syncProtocol.readSyncMessage(decoder, encoder, room.doc, conn);
        if (encoding.length(encoder) > 1) {
          this.#send(conn.ws, encoding.toUint8Array(encoder));
        }
        break;
      }
      case messageQueryAwareness: {
        const encoder = encoding.createEncoder();
        encoding.writeVarUint(encoder, messageAwareness);
        encoding.writeVarUint8Array(
          encoder,
          awarenessProtocol.encodeAwarenessUpdate(
            room.awareness,
            Array.from(room.awareness.getStates().keys()),
          ),
        );
        this.#send(conn.ws, encoding.toUint8Array(encoder));
        break;
      }
      case messageAwareness: {
        const update = decoding.readVarUint8Array(decoder);
        for (const clientId of decodeAwarenessClientIds(update)) conn.clientIds.add(clientId);
        awarenessProtocol.applyAwarenessUpdate(room.awareness, update, conn);
        const encoder = encoding.createEncoder();
        encoding.writeVarUint(encoder, messageAwareness);
        encoding.writeVarUint8Array(encoder, update);
        const message = encoding.toUint8Array(encoder);
        for (const other of room.conns) {
          if (other !== conn) this.#send(other.ws, message);
        }
        break;
      }
      case messageAuth:
        // Authorization happens at connect time; a mid-connection auth
        // message has nothing to authorize. Ignore it.
        break;
      default:
        console.warn(`[collab] unknown message type ${messageType} for ${room.documentId}`);
    }
  }

  async #removeConnection(room, conn) {
    if (!room.conns.delete(conn)) return;
    // Withdraw this connection's awareness states so other clients stop
    // rendering its cursor, and broadcast the removal.
    if (conn.clientIds.size > 0) {
      const removed = [...conn.clientIds].filter((id) => room.awareness.getStates().has(id));
      if (removed.length > 0) {
        awarenessProtocol.removeAwarenessStates(room.awareness, removed, conn);
        const encoder = encoding.createEncoder();
        encoding.writeVarUint(encoder, messageAwareness);
        encoding.writeVarUint8Array(
          encoder,
          awarenessProtocol.encodeAwarenessUpdate(room.awareness, removed),
        );
        const message = encoding.toUint8Array(encoder);
        for (const other of room.conns) this.#send(other.ws, message);
      }
    }
    if (room.conns.size === 0) {
      // Last client out: snapshot dirty state, then drop the room so a
      // later reconnect reloads from PostgreSQL. Destroy the room's
      // Awareness and Doc too — Awareness runs a 3 s housekeeping
      // interval that would otherwise leak one timer per dropped room.
      if (room.dirty) await this.#writeSnapshot(room);
      room.awareness.destroy();
      room.doc.destroy();
      this.#rooms.delete(room.documentId);
    }
  }

  #send(ws, message) {
    if (ws.readyState === WebSocket.OPEN) ws.send(message);
  }
}

/** Extracts the client ids carried by an awareness update. */
export function decodeAwarenessClientIds(update) {
  const decoder = decoding.createDecoder(update);
  const count = decoding.readVarUint(decoder);
  const ids = [];
  for (let i = 0; i < count; i++) {
    ids.push(decoding.readVarUint(decoder));
    decoding.readVarUint(decoder); // clock
    decoding.readVarString(decoder); // state JSON
  }
  return ids;
}
