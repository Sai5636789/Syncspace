import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Client, IMessage } from '@stomp/stompjs';
import { AuthService } from './auth.service';
import {
  ChatStompService,
  PRESENCE_HEARTBEAT_MS,
  STOMP_CLIENT_FACTORY,
  TYPING_TIMEOUT_MS,
  computeBackoffDelay,
} from './chat-stomp.service';
import { WsEnvelope } from '../models/chat.model';

/** Minimal stand-in for the @stomp/stompjs Client; callbacks are fired by hand. */
class FakeClient {
  webSocketFactory: (() => unknown) | undefined;
  connectHeaders: Record<string, string> = {};
  reconnectDelay = 0;
  heartbeatIncoming = 0;
  heartbeatOutgoing = 0;
  onConnect: (() => void) | null = null;
  onStompError: ((frame: { headers?: Record<string, string>; body?: string }) => void) | null = null;
  onWebSocketClose: (() => void) | null = null;
  activated = false;
  deactivated = false;
  published: { destination: string; body: string }[] = [];
  handlers = new Map<string, (message: IMessage) => void>();

  activate(): void {
    this.activated = true;
  }

  async deactivate(): Promise<void> {
    this.deactivated = true;
  }

  publish(payload: { destination: string; body: string }): void {
    this.published.push(payload);
  }

  subscribe(destination: string, callback: (message: IMessage) => void): { unsubscribe: () => void } {
    this.handlers.set(destination, callback);
    return { unsubscribe: () => this.handlers.delete(destination) };
  }

  fireConnect(): void {
    this.onConnect?.();
  }

  fireFrame(destination: string, body: string): void {
    this.handlers.get(destination)?.({ body } as IMessage);
  }

  fireStompError(message: string): void {
    this.onStompError?.({ headers: { message }, body: '' });
  }

  fireClose(): void {
    this.onWebSocketClose?.();
  }
}

function envelope(eventId: string, eventType: string, payload: unknown): string {
  const env: WsEnvelope = {
    eventId,
    eventType,
    workspaceId: 'ws-1',
    resourceId: null,
    actorId: 'u-2',
    timestamp: '2026-11-25T00:00:00Z',
    version: 1,
    payload,
  };
  return JSON.stringify(env);
}

describe('computeBackoffDelay', () => {
  it('doubles from 1s and caps at 30s', () => {
    const noJitter = () => 0;
    expect(computeBackoffDelay(0, noJitter)).toBe(1000);
    expect(computeBackoffDelay(1, noJitter)).toBe(2000);
    expect(computeBackoffDelay(2, noJitter)).toBe(4000);
    expect(computeBackoffDelay(5, noJitter)).toBe(30000);
    expect(computeBackoffDelay(10, noJitter)).toBe(30000);
  });

  it('adds at most 25% jitter', () => {
    expect(computeBackoffDelay(0, () => 1)).toBe(1250);
    expect(computeBackoffDelay(1, () => 1)).toBe(2500);
  });
});

describe('ChatStompService', () => {
  const clients: FakeClient[] = [];
  const authFake = {
    token: 'token-1' as string | null,
    currentUser: signal({ id: 'u-1', email: 'sai@example.com', displayName: 'Sai' }),
    refresh: vi.fn(() => {
      authFake.token = 'token-2';
      return of({
        user: { id: 'u-1', email: 'sai@example.com', displayName: 'Sai' },
        accessToken: 'token-2',
        refreshToken: 'refresh-2',
        expiresInSeconds: 900,
      });
    }),
  };

  let service: ChatStompService;

  beforeEach(() => {
    vi.useFakeTimers();
    clients.length = 0;
    authFake.token = 'token-1';
    authFake.refresh.mockClear();
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authFake },
        {
          provide: STOMP_CLIENT_FACTORY,
          useValue: () => {
            const client = new FakeClient();
            clients.push(client);
            return client as unknown as Client;
          },
        },
      ],
    });
    service = TestBed.inject(ChatStompService);
  });

  afterEach(() => {
    service.disconnect();
    vi.useRealTimers();
  });

  function connectAndFire(): FakeClient {
    service.connect('ws-1');
    const client = clients[0];
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer token-1' });
    client.fireConnect();
    expect(service.status()).toBe('connected');
    return client;
  }

  it('connects with the bearer token and subscribes to message, typing, and presence topics', () => {
    const client = connectAndFire();
    expect(client.activated).toBe(true);
    expect(client.heartbeatIncoming).toBe(10000);
    expect(client.heartbeatOutgoing).toBe(10000);
    expect([...client.handlers.keys()]).toEqual([
      '/topic/workspaces/ws-1/messages',
      '/topic/workspaces/ws-1/typing',
      '/topic/workspaces/ws-1/presence',
    ]);
  });

  it('deduplicates events by eventId', () => {
    const client = connectAndFire();
    const seen: WsEnvelope[] = [];
    service.messageEvents$.subscribe((event) => seen.push(event));
    const frame = envelope('evt-1', 'MESSAGE_CREATED', {
      channelId: 'ch-1',
      message: { id: 'm-1', channelId: 'ch-1', authorId: 'u-2', body: 'hi', createdAt: '2026-11-25T00:00:00Z' },
    });
    client.fireFrame('/topic/workspaces/ws-1/messages', frame);
    client.fireFrame('/topic/workspaces/ws-1/messages', frame);
    expect(seen).toHaveLength(1);
  });

  it('passes distinct events through', () => {
    const client = connectAndFire();
    const seen: WsEnvelope[] = [];
    service.messageEvents$.subscribe((event) => seen.push(event));
    client.fireFrame('/topic/workspaces/ws-1/messages', envelope('evt-1', 'MESSAGE_CREATED', { channelId: 'ch-1', message: { id: 'm-1' } }));
    client.fireFrame('/topic/workspaces/ws-1/messages', envelope('evt-2', 'MESSAGE_CREATED', { channelId: 'ch-1', message: { id: 'm-2' } }));
    expect(seen).toHaveLength(2);
  });

  it('reconnects with backoff, re-subscribes, and emits reconnected$', async () => {
    const first = connectAndFire();
    const reconnected: void[] = [];
    service.reconnected$.subscribe(() => reconnected.push(undefined));
    first.fireClose();
    expect(service.status()).toBe('reconnecting');
    await vi.advanceTimersByTimeAsync(2000);
    expect(clients).toHaveLength(2);
    clients[1].fireConnect();
    expect(service.status()).toBe('connected');
    expect([...clients[1].handlers.keys()]).toEqual([
      '/topic/workspaces/ws-1/messages',
      '/topic/workspaces/ws-1/typing',
      '/topic/workspaces/ws-1/presence',
    ]);
    expect(reconnected).toHaveLength(1);
  });

  it('refreshes the token once on an auth STOMP error and reconnects', async () => {
    const first = connectAndFire();
    first.fireStompError('Unauthorized: token expired');
    await vi.advanceTimersByTimeAsync(0);
    expect(authFake.refresh).toHaveBeenCalledTimes(1);
    expect(clients).toHaveLength(2);
    expect(clients[1].connectHeaders).toEqual({ Authorization: 'Bearer token-2' });
  });

  it('publishes sends, typing, and acks to the workspace destinations', () => {
    const client = connectAndFire();
    // Connecting sends one presence heartbeat immediately.
    expect(client.published[0].destination).toBe('/app/workspaces/ws-1/presence');
    expect(JSON.parse(client.published[0].body)).toEqual({});
    const clientMessageId = service.sendMessage('ch-1', 'hello');
    expect(client.published[1].destination).toBe('/app/workspaces/ws-1/chat');
    expect(JSON.parse(client.published[1].body)).toEqual({
      channelId: 'ch-1',
      clientMessageId,
      body: 'hello',
    });
    service.sendTyping('ch-1', true);
    expect(JSON.parse(client.published[2].body)).toEqual({ channelId: 'ch-1', typing: true });
    service.sendAck('m-9');
    expect(JSON.parse(client.published[3].body)).toEqual({ messageId: 'm-9' });
  });

  it('sends a presence heartbeat on connect and every 15s until disconnect', async () => {
    const client = connectAndFire();
    const heartbeats = () =>
      client.published.filter((item) => item.destination === '/app/workspaces/ws-1/presence');
    expect(heartbeats()).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(PRESENCE_HEARTBEAT_MS);
    expect(heartbeats()).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(PRESENCE_HEARTBEAT_MS);
    expect(heartbeats()).toHaveLength(3);
    service.disconnect();
    await vi.advanceTimersByTimeAsync(PRESENCE_HEARTBEAT_MS * 2);
    expect(heartbeats()).toHaveLength(3);
  });

  it('does not throw when heartbeating while offline', () => {
    expect(() => service.sendHeartbeat()).not.toThrow();
  });

  it('updates presence from PRESENCE_CHANGED and deduplicates by eventId', () => {
    const client = connectAndFire();
    const online = envelope('evt-p1', 'PRESENCE_CHANGED', {
      userId: 'u-2',
      status: 'online',
      lastSeenAt: '2026-11-26T12:00:00Z',
    });
    client.fireFrame('/topic/workspaces/ws-1/presence', online);
    client.fireFrame('/topic/workspaces/ws-1/presence', online);
    expect(service.presence()['u-2']).toEqual({ status: 'online', lastSeenAt: '2026-11-26T12:00:00Z' });
    client.fireFrame(
      '/topic/workspaces/ws-1/presence',
      envelope('evt-p2', 'PRESENCE_CHANGED', {
        userId: 'u-2',
        status: 'offline',
        lastSeenAt: '2026-11-26T12:05:00Z',
      }),
    );
    expect(service.presence()['u-2']).toEqual({ status: 'offline', lastSeenAt: '2026-11-26T12:05:00Z' });
  });

  it('seeds presence from a REST snapshot', () => {
    service.applyPresenceSnapshot([
      {
        userId: 'u-1',
        email: 'sai@example.com',
        displayName: 'Sai',
        role: 'OWNER',
        joinedAt: '2026-11-24T00:00:00Z',
        status: 'online',
        lastSeenAt: '2026-11-26T12:00:00Z',
      },
      {
        userId: 'u-2',
        email: 'ana@example.com',
        displayName: 'Ana',
        role: 'MEMBER',
        joinedAt: '2026-11-24T00:00:00Z',
        status: 'offline',
        lastSeenAt: '2026-11-26T11:00:00Z',
      },
    ]);
    expect(service.presence()).toEqual({
      'u-1': { status: 'online', lastSeenAt: '2026-11-26T12:00:00Z' },
      'u-2': { status: 'offline', lastSeenAt: '2026-11-26T11:00:00Z' },
    });
  });

  it('throws when sending while offline', () => {
    expect(() => service.sendMessage('ch-1', 'hello')).toThrow('Not connected');
  });

  it('shows a typing user and clears them after the timeout', async () => {
    const client = connectAndFire();
    client.fireFrame(
      '/topic/workspaces/ws-1/typing',
      envelope('evt-t1', 'TYPING_STARTED', { channelId: 'ch-1', userId: 'u-2', displayName: 'Ana' }),
    );
    expect(service.typing()['ch-1']).toEqual([{ userId: 'u-2', displayName: 'Ana' }]);
    await vi.advanceTimersByTimeAsync(TYPING_TIMEOUT_MS + 1500);
    expect(service.typing()['ch-1']).toBeUndefined();
  });

  it('clears a typing user immediately on TYPING_STOPPED', () => {
    const client = connectAndFire();
    client.fireFrame(
      '/topic/workspaces/ws-1/typing',
      envelope('evt-t1', 'TYPING_STARTED', { channelId: 'ch-1', userId: 'u-2', displayName: 'Ana' }),
    );
    client.fireFrame(
      '/topic/workspaces/ws-1/typing',
      envelope('evt-t2', 'TYPING_STOPPED', { channelId: 'ch-1', userId: 'u-2', displayName: 'Ana' }),
    );
    expect(service.typing()['ch-1']).toBeUndefined();
  });

  it('disconnect stops reconnecting and deactivates the client', async () => {
    const client = connectAndFire();
    client.fireClose();
    expect(service.status()).toBe('reconnecting');
    service.disconnect();
    expect(service.status()).toBe('offline');
    expect(client.deactivated).toBe(true);
    await vi.advanceTimersByTimeAsync(60000);
    expect(clients).toHaveLength(1);
  });
});
