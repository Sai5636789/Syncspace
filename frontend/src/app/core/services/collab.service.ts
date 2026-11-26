import { Injectable, InjectionToken, Signal, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import * as Y from 'yjs';
import { WebsocketProvider } from 'y-websocket';
import { IndexeddbPersistence } from 'y-indexeddb';
import { Awareness } from 'y-protocols/awareness';
import { AuthService } from './auth.service';

/** y-websocket provider connection state (from its `status` event). */
export type CollabConnectionStatus = 'connecting' | 'connected' | 'disconnected';

/**
 * What the editor's save badge shows. These are wired to real provider
 * events, never faked:
 *
 * - `connecting` — no sync has completed yet.
 * - `saving` — a local edit was made after the last sync and the socket
 *   has not yet flushed it (see the flush check below).
 * - `saved` — the provider reports synced and the socket has no buffered
 *   local update outstanding. Note the client cannot observe the
 *   server's PostgreSQL write directly: the collaboration server
 *   persists every update it receives (relay first, persist second),
 *   so "sent over an open, synced socket" is the strongest signal the
 *   client has; it is not presented as a database acknowledgement.
 * - `offline` — the socket cannot reach the server (or never connected).
 *   Edits made now are stored in IndexedDB and sync on reconnect.
 * - `reconnecting` — the socket dropped after a successful connection
 *   and y-websocket is backing off and retrying.
 */
export type SaveState = 'connecting' | 'saving' | 'saved' | 'offline' | 'reconnecting';

export interface Collaborator {
  clientId: number;
  userId: string | null;
  name: string;
  color: string;
  isSelf: boolean;
}

export interface CollabSession {
  readonly doc: Y.Doc;
  readonly provider: WebsocketProvider;
  readonly awareness: Awareness;
  readonly status: Signal<CollabConnectionStatus>;
  readonly saveState: Signal<SaveState>;
  /** True once the server closed the connection with 4401 (no access). */
  readonly forbidden: Signal<boolean>;
  readonly collaborators: Signal<Collaborator[]>;
  destroy(): void;
}

/** Close code the collaboration server uses for unauthorized connections. */
export const COLLAB_UNAUTHORIZED_CLOSE_CODE = 4401;
/** Quiet period after a local edit before the socket is checked for a flush. */
export const SAVE_FLUSH_DELAY_MS = 500;
/** Refresh the access token this far before it expires. */
export const TOKEN_REFRESH_SKEW_MS = 60_000;
/** WebSocket readyState OPEN is 1 (RFC 6455); a named constant avoids relying on the global WebSocket constructor existing (jsdom). */
const WEBSOCKET_OPEN_STATE = 1;

/** Per-user cursor colors, assigned deterministically from the user id. */
const CURSOR_COLORS = ['#d32f2f', '#1976d2', '#388e3c', '#ef6c00', '#7b1fa2', '#00897b', '#c2185b', '#5d4037'];

export function userColorFor(userId: string): string {
  let hash = 0;
  for (const char of userId) {
    hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  }
  return CURSOR_COLORS[hash % CURSOR_COLORS.length];
}

/** Expiry of a JWT access token in epoch ms, or null when it cannot be read. */
export function tokenExpiresAtMs(token: string): number | null {
  try {
    const payloadSegment = token.split('.')[1];
    if (!payloadSegment) {
      return null;
    }
    const payload = JSON.parse(
      atob(payloadSegment.replace(/-/g, '+').replace(/_/g, '/')),
    ) as { exp?: unknown };
    return typeof payload.exp === 'number' ? payload.exp * 1000 : null;
  } catch {
    return null;
  }
}

/**
 * Collaboration server base URL. The dev server proxies /collab to the
 * Node service on :3001 (proxy.conf.json); in Docker Compose Nginx does
 * the same, so the URL is always same-origin.
 */
export function collabServerUrl(): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${location.host}/collab`;
}

export type CollabProviderFactory = (
  serverUrl: string,
  roomName: string,
  doc: Y.Doc,
  opts: ConstructorParameters<typeof WebsocketProvider>[3],
) => WebsocketProvider;

/** Factory for the underlying y-websocket provider; overridden in tests with a fake. */
export const COLLAB_PROVIDER_FACTORY = new InjectionToken<CollabProviderFactory>(
  'COLLAB_PROVIDER_FACTORY',
  {
    providedIn: 'root',
    factory: () => (serverUrl, roomName, doc, opts) =>
      new WebsocketProvider(serverUrl, roomName, doc, opts),
  },
);

/**
 * Owns one document's Yjs session: the shared Y.Doc, the y-websocket
 * provider to the collaboration server, local IndexedDB persistence, and
 * the awareness state that backs remote cursors and the participant list.
 *
 * The provider connects with `?token=<access-token>`; the server verifies
 * the JWT signature and document membership and closes unauthorized
 * connections with 4401, which y-websocket treats as terminal (no
 * reconnect) and surfaces here as `forbidden`. The token is refreshed
 * first when it is missing or about to expire, reusing the same refresh
 * endpoint the HTTP auth interceptor uses.
 */
@Injectable({ providedIn: 'root' })
export class CollabService {
  private readonly auth = inject(AuthService);
  private readonly createProvider = inject(COLLAB_PROVIDER_FACTORY);

  async connect(documentId: string): Promise<CollabSession> {
    const token = await this.ensureFreshToken();
    const doc = new Y.Doc();
    const awareness = new Awareness(doc);
    const persistence = createPersistence(documentId, doc);
    const provider = this.createProvider(collabServerUrl(), documentId, doc, {
      params: { token },
      awareness,
      connect: true,
    });
    return new CollabSessionImpl(doc, provider, awareness, persistence);
  }

  private async ensureFreshToken(): Promise<string> {
    let token = this.auth.token;
    const expiresAt = token === null ? null : tokenExpiresAtMs(token);
    if (token === null || expiresAt === null || expiresAt - Date.now() < TOKEN_REFRESH_SKEW_MS) {
      try {
        const res = await firstValueFrom(this.auth.refresh());
        token = res.accessToken;
      } catch {
        throw new Error('Your session expired. Sign in again to open this document.');
      }
    }
    return token;
  }
}

/**
 * Local persistence for offline edits. y-indexeddb stores every doc
 * update (debounced ~1 s) under `syncspace-doc-<id>` and replays it into
 * the doc on the next open, so edits made while offline survive a reload
 * and sync once the provider reconnects. Skipped where IndexedDB does not
 * exist (unit tests under jsdom); sync still works without it.
 */
function createPersistence(documentId: string, doc: Y.Doc): IndexeddbPersistence | null {
  if (typeof indexedDB === 'undefined') {
    return null;
  }
  try {
    return new IndexeddbPersistence(`syncspace-doc-${documentId}`, doc);
  } catch {
    return null;
  }
}

class CollabSessionImpl implements CollabSession {
  readonly status = signal<CollabConnectionStatus>('connecting');
  readonly saveState = signal<SaveState>('connecting');
  readonly forbidden = signal(false);
  readonly collaborators = signal<Collaborator[]>([]);

  private everConnected = false;
  private everSynced = false;
  private pendingLocalUpdate = false;
  private flushTimer: ReturnType<typeof setTimeout> | null = null;
  private destroyed = false;

  constructor(
    readonly doc: Y.Doc,
    readonly provider: WebsocketProvider,
    readonly awareness: Awareness,
    private readonly persistence: IndexeddbPersistence | null,
  ) {
    provider.on('status', this.handleStatus);
    provider.on('sync', this.handleSync);
    provider.on('connection-error', this.handleConnectionError);
    provider.on('closed', this.handleClosed);
    doc.on('update', this.handleDocUpdate);
    awareness.on('change', this.refreshCollaborators);
    this.refreshCollaborators();
  }

  destroy(): void {
    if (this.destroyed) {
      return;
    }
    this.destroyed = true;
    this.clearFlushTimer();
    this.provider.off('status', this.handleStatus);
    this.provider.off('sync', this.handleSync);
    this.provider.off('connection-error', this.handleConnectionError);
    this.provider.off('closed', this.handleClosed);
    this.doc.off('update', this.handleDocUpdate);
    this.awareness.off('change', this.refreshCollaborators);
    this.provider.destroy();
    this.persistence?.destroy();
    // Awareness runs a housekeeping interval cleared only by destroy();
    // without this a dropped session leaks one timer per document open.
    this.awareness.destroy();
    this.doc.destroy();
  }

  private readonly handleStatus = (event: { status: CollabConnectionStatus }): void => {
    if (this.destroyed) {
      return;
    }
    this.status.set(event.status);
    if (event.status === 'connected') {
      this.everConnected = true;
    }
    this.recomputeSaveState();
  };

  private readonly handleSync = (synced: boolean): void => {
    if (this.destroyed) {
      return;
    }
    if (synced) {
      this.everSynced = true;
      if (this.pendingLocalUpdate) {
        this.scheduleFlushCheck();
      } else {
        this.recomputeSaveState();
      }
    } else {
      this.recomputeSaveState();
    }
  };

  /**
   * A socket error before the first successful connection means the
   * server is unreachable right now — report offline rather than leaving
   * the badge on "Connecting…" while y-websocket backs off and retries.
   * Later status/sync events overwrite this if the server answers.
   */
  private readonly handleConnectionError = (): void => {
    if (!this.destroyed && !this.everConnected) {
      this.saveState.set('offline');
    }
  };

  private readonly handleClosed = (event: { code: number }): void => {
    if (this.destroyed) {
      return;
    }
    if (event.code === COLLAB_UNAUTHORIZED_CLOSE_CODE) {
      // y-websocket already stopped reconnecting for 4400–4499 closes.
      this.forbidden.set(true);
      this.clearFlushTimer();
    }
  };

  /**
   * Marks a local edit as unsaved. Updates whose origin is the provider
   * arrived from a remote peer and need no save tracking; updates before
   * the first sync are the IndexedDB restore (or the initial empty doc)
   * and are not local edits either.
   */
  private readonly handleDocUpdate = (_update: Uint8Array, origin: unknown): void => {
    if (this.destroyed || !this.everSynced || origin === this.provider) {
      return;
    }
    this.pendingLocalUpdate = true;
    if (this.status() === 'connected' && this.provider.synced) {
      this.saveState.set('saving');
      this.scheduleFlushCheck();
    }
  };

  /**
   * After a quiet period, a local update counts as saved once the
   * provider is synced and the socket has nothing buffered — i.e. the
   * update has actually left this client. While still connected but not
   * yet flushed, keep waiting; a disconnect hands control back to the
   * status/sync handlers, which resume the check on reconnect.
   */
  private scheduleFlushCheck(): void {
    this.clearFlushTimer();
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null;
      if (this.destroyed || !this.pendingLocalUpdate) {
        return;
      }
      const ws = this.provider.ws;
      const flushed =
        this.provider.synced &&
        this.status() === 'connected' &&
        ws !== null &&
        ws.readyState === WEBSOCKET_OPEN_STATE &&
        ws.bufferedAmount === 0;
      if (flushed) {
        this.pendingLocalUpdate = false;
        this.saveState.set('saved');
      } else if (this.status() === 'connected') {
        this.scheduleFlushCheck();
      }
    }, SAVE_FLUSH_DELAY_MS);
  }

  private recomputeSaveState(): void {
    if (this.forbidden()) {
      return;
    }
    const status = this.status();
    if (status === 'connected') {
      if (this.pendingLocalUpdate) {
        this.saveState.set('saving');
      } else if (this.provider.synced) {
        this.saveState.set('saved');
      } else {
        this.saveState.set('connecting');
      }
    } else if (status === 'disconnected') {
      this.saveState.set(this.everConnected ? 'reconnecting' : 'offline');
    } else {
      this.saveState.set(this.everConnected ? 'reconnecting' : 'connecting');
    }
  }

  private readonly refreshCollaborators = (): void => {
    const list: Collaborator[] = [];
    for (const [clientId, state] of this.awareness.getStates()) {
      const user = (state as { user?: { name?: string; color?: string; userId?: string } }).user;
      if (!user?.name) {
        continue;
      }
      list.push({
        clientId,
        userId: user.userId ?? null,
        name: user.name,
        color: user.color ?? '#888888',
        isSelf: clientId === this.doc.clientID,
      });
    }
    list.sort((a, b) => (a.isSelf ? -1 : b.isSelf ? 1 : a.name.localeCompare(b.name)));
    this.collaborators.set(list);
  };

  private clearFlushTimer(): void {
    if (this.flushTimer !== null) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }
  }
}
