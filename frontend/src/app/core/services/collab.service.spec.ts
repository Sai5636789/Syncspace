import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as Y from 'yjs';
import { WebsocketProvider } from 'y-websocket';
import {
  Awareness,
  applyAwarenessUpdate,
  encodeAwarenessUpdate,
} from 'y-protocols/awareness';
import { AuthService } from './auth.service';
import {
  COLLAB_PROVIDER_FACTORY,
  COLLAB_UNAUTHORIZED_CLOSE_CODE,
  CollabService,
  CollabSession,
  SAVE_FLUSH_DELAY_MS,
  tokenExpiresAtMs,
  userColorFor,
} from './collab.service';

/** Minimal stand-in for WebsocketProvider; events are fired by hand. */
class FakeProvider {
  readonly awareness: Awareness;
  synced = false;
  ws: { readyState: number; bufferedAmount: number } | null = null;
  shouldConnect = true;
  readonly serverUrl: string;
  readonly roomName: string;
  readonly params: Record<string, string>;
  disconnect = vi.fn(() => {
    this.shouldConnect = false;
  });
  destroy = vi.fn();

  private handlers = new Map<string, Set<(...args: unknown[]) => void>>();

  constructor(
    serverUrl: string,
    roomName: string,
    doc: Y.Doc,
    opts: { awareness?: Awareness; params?: Record<string, string> },
  ) {
    this.serverUrl = serverUrl;
    this.roomName = roomName;
    this.params = opts.params ?? {};
    this.awareness = opts.awareness ?? new Awareness(doc);
  }

  on(event: string, cb: (...args: unknown[]) => void): void {
    let set = this.handlers.get(event);
    if (!set) {
      set = new Set();
      this.handlers.set(event, set);
    }
    set.add(cb);
  }

  off(event: string, cb: (...args: unknown[]) => void): void {
    this.handlers.get(event)?.delete(cb);
  }

  emit(event: string, ...args: unknown[]): void {
    for (const cb of [...(this.handlers.get(event) ?? [])]) {
      cb(...args);
    }
  }
}

function fakeJwt(expSeconds: number): string {
  const payload = btoa(JSON.stringify({ exp: expSeconds }))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `header.${payload}.sig`;
}

describe('CollabService', () => {
  let session: CollabSession | null = null;
  let lastFake: FakeProvider | null = null;
  let factoryArgs: { serverUrl: string; roomName: string } | null = null;

  async function setup(token: string | null, refreshResult?: unknown, connectNow = true) {
    lastFake = null;
    factoryArgs = null;
    const refresh = vi.fn(() =>
      refreshResult instanceof Error
        ? throwError(() => refreshResult)
        : of({
            user: { id: 'u-1', email: 'sai@example.com', displayName: 'Sai' },
            accessToken: 'fresh-token',
            refreshToken: 'fresh-refresh',
            expiresInSeconds: 900,
          }),
    );
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: {
            token,
            currentUser: signal({ id: 'u-1', email: 'sai@example.com', displayName: 'Sai' }),
            refresh,
          },
        },
        {
          provide: COLLAB_PROVIDER_FACTORY,
          useValue: (
            serverUrl: string,
            roomName: string,
            doc: Y.Doc,
            opts: { awareness?: Awareness; params?: Record<string, string> },
          ) => {
            factoryArgs = { serverUrl, roomName };
            lastFake = new FakeProvider(serverUrl, roomName, doc, opts);
            return lastFake as unknown as WebsocketProvider;
          },
        },
      ],
    });
    const service = TestBed.inject(CollabService);
    if (connectNow) {
      session = await service.connect('doc-1');
    }
    return { service, refresh, fake: lastFake as unknown as FakeProvider };
  }

  afterEach(() => {
    session?.destroy();
    session = null;
    vi.useRealTimers();
  });

  function connectFake(fake: FakeProvider): void {
    fake.synced = true;
    fake.ws = { readyState: 1, bufferedAmount: 0 };
    fake.emit('status', { status: 'connected' });
    fake.emit('sync', true);
  }

  it('connects with the current token when it is fresh and targets /collab/{documentId}', async () => {
    const token = fakeJwt(Math.floor(Date.now() / 1000) + 900);
    const { refresh, fake } = await setup(token);
    expect(refresh).not.toHaveBeenCalled();
    expect(factoryArgs?.serverUrl.endsWith('/collab')).toBe(true);
    expect(factoryArgs?.roomName).toBe('doc-1');
    expect(fake.params['token']).toBe(token);
    expect(session?.status()).toBe('connecting');
    expect(session?.saveState()).toBe('connecting');
  });

  it('refreshes the token when it expires within a minute', async () => {
    const token = fakeJwt(Math.floor(Date.now() / 1000) + 30);
    const { refresh, fake } = await setup(token);
    expect(refresh).toHaveBeenCalled();
    expect(fake.params['token']).toBe('fresh-token');
  });

  it('reports a friendly error when refresh fails with no usable token', async () => {
    const { service } = await setup(null, new Error('no refresh token'), false);
    await expect(service.connect('doc-1')).rejects.toThrow('Your session expired');
  });

  it('moves connecting → saved once connected and synced', async () => {
    const { fake } = await setup(fakeJwt(Math.floor(Date.now() / 1000) + 900));
    fake.emit('status', { status: 'connected' });
    expect(session?.status()).toBe('connected');
    expect(session?.saveState()).toBe('connecting');
    fake.synced = true;
    fake.emit('sync', true);
    expect(session?.saveState()).toBe('saved');
  });

  it('marks a local edit saving, then saved once the socket flushes', async () => {
    const { fake } = await setup(fakeJwt(Math.floor(Date.now() / 1000) + 900));
    vi.useFakeTimers();
    connectFake(fake);
    expect(session?.saveState()).toBe('saved');
    session?.doc.getXmlFragment('content').insert(0, [new Y.XmlText('hello')]);
    expect(session?.saveState()).toBe('saving');
    vi.advanceTimersByTime(SAVE_FLUSH_DELAY_MS);
    expect(session?.saveState()).toBe('saved');
  });

  it('does not mark a remote update as a local edit', async () => {
    const { fake } = await setup(fakeJwt(Math.floor(Date.now() / 1000) + 900));
    vi.useFakeTimers();
    connectFake(fake);
    const other = new Y.Doc();
    other.getXmlFragment('content').insert(0, [new Y.XmlText('from ana')]);
    Y.applyUpdate(session?.doc as Y.Doc, Y.encodeStateAsUpdate(other), fake);
    expect(session?.saveState()).toBe('saved');
    expect(vi.getTimerCount()).toBe(0);
    other.destroy();
  });

  it('reports reconnecting after a drop and offline when the server never answers', async () => {
    const { fake } = await setup(fakeJwt(Math.floor(Date.now() / 1000) + 900));
    connectFake(fake);
    fake.emit('status', { status: 'disconnected' });
    expect(session?.status()).toBe('disconnected');
    expect(session?.saveState()).toBe('reconnecting');
    fake.emit('status', { status: 'connecting' });
    expect(session?.saveState()).toBe('reconnecting');

    const second = await TestBed.inject(CollabService).connect('doc-2');
    try {
      const fake2 = lastFake as unknown as FakeProvider;
      fake2.emit('connection-error', new Event('error'));
      expect(second.saveState()).toBe('offline');
    } finally {
      second.destroy();
    }
  });

  it('reports forbidden on a 4401 close', async () => {
    const { fake } = await setup(fakeJwt(Math.floor(Date.now() / 1000) + 900));
    fake.emit('closed', { code: COLLAB_UNAUTHORIZED_CLOSE_CODE, reason: 'unauthorized' });
    expect(session?.forbidden()).toBe(true);
  });

  it('lists collaborators from awareness, self first', async () => {
    const { fake } = await setup(fakeJwt(Math.floor(Date.now() / 1000) + 900));
    fake.awareness.setLocalStateField('user', { name: 'Sai', color: '#d32f2f', userId: 'u-1' });
    const remoteDoc = new Y.Doc();
    const remoteAwareness = new Awareness(remoteDoc);
    remoteAwareness.setLocalStateField('user', { name: 'Ana', color: '#1976d2', userId: 'u-2' });
    applyAwarenessUpdate(
      fake.awareness,
      encodeAwarenessUpdate(remoteAwareness, [remoteDoc.clientID]),
      'test',
    );
    const collaborators = session?.collaborators() ?? [];
    expect(collaborators.map((person) => person.name)).toEqual(['Sai', 'Ana']);
    expect(collaborators[0].isSelf).toBe(true);
    expect(collaborators[1].isSelf).toBe(false);
    expect(collaborators[1].color).toBe('#1976d2');
    remoteAwareness.destroy();
    remoteDoc.destroy();
  });

  it('destroys the provider, awareness, and doc on destroy', async () => {
    const { fake } = await setup(fakeJwt(Math.floor(Date.now() / 1000) + 900));
    session?.destroy();
    session = null;
    expect(fake.destroy).toHaveBeenCalled();
  });

  it('assigns stable, distinct cursor colors per user', () => {
    expect(userColorFor('u-1')).toBe(userColorFor('u-1'));
    expect(userColorFor('u-1')).not.toBe(userColorFor('u-2'));
  });

  it('reads a JWT expiry and returns null for a malformed token', () => {
    const exp = Math.floor(Date.now() / 1000) + 600;
    expect(tokenExpiresAtMs(fakeJwt(exp))).toBe(exp * 1000);
    expect(tokenExpiresAtMs('not-a-token')).toBeNull();
  });
});
