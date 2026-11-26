import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import { Subject, firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';
import { TypingPayload, TypingUser, WsEnvelope } from '../models/chat.model';
import { PresenceChangedPayload, PresenceMember, PresenceState } from '../models/presence.model';

/** Factory for the underlying STOMP client; overridden in tests with a fake. */
export const STOMP_CLIENT_FACTORY = new InjectionToken<() => Client>('STOMP_CLIENT_FACTORY', {
  providedIn: 'root',
  factory: () => () => new Client(),
});

export type ConnectionStatus = 'connected' | 'reconnecting' | 'offline';

/** Base delays for reconnect attempts: 1s, 2s, 4s, … capped at 30s. */
export const RECONNECT_BASE_MS = 1000;
export const RECONNECT_MAX_MS = 30000;
/** A typing indicator clears this long after the last TYPING_STARTED without a TYPING_STOPPED. */
export const TYPING_TIMEOUT_MS = 3000;
/** Presence heartbeat interval. The server TTL is 45 s, so two missed beats are tolerated. */
export const PRESENCE_HEARTBEAT_MS = 15000;

/**
 * Delay before reconnect attempt `attempt` (0-based), with up to 25% jitter.
 * `random` is injectable so tests can pin the jitter.
 */
export function computeBackoffDelay(attempt: number, random: () => number = Math.random): number {
  const base = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** attempt);
  return base * (1 + random() * 0.25);
}

function newClientMessageId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/**
 * Owns the STOMP-over-WebSocket connection for one workspace.
 *
 * Native WebSocket at /ws (no SockJS); the dev server proxies /ws to the
 * backend. CONNECT carries `Authorization: Bearer <access-token>`. On an
 * auth rejection the token is refreshed once and the connection retried.
 * Any other drop reconnects with exponential backoff; after a reconnect
 * the service re-subscribes and emits `reconnected$` so the UI can recover
 * missed messages from REST. Incoming events are deduplicated by eventId.
 * While connected, the service heartbeats presence every
 * PRESENCE_HEARTBEAT_MS and tracks live presence in the `presence` signal.
 */
@Injectable({ providedIn: 'root' })
export class ChatStompService {
  private readonly auth = inject(AuthService);
  private readonly createClient = inject(STOMP_CLIENT_FACTORY);

  /** Current connection state, for the status indicator. */
  readonly status = signal<ConnectionStatus>('offline');
  /** Typing users per channel id. Entries expire TYPING_TIMEOUT_MS after the last update. */
  readonly typing = signal<Readonly<Record<string, TypingUser[]>>>({});
  /**
   * Live presence per user id, from PRESENCE_CHANGED envelopes on the
   * workspace presence topic. Seeded from REST by
   * {@link applyPresenceSnapshot} (the recovery source after a reconnect).
   */
  readonly presence = signal<Readonly<Record<string, PresenceState>>>({});
  /** MESSAGE_CREATED / MESSAGE_UPDATED / MESSAGE_DELETED envelopes, deduplicated by eventId. */
  readonly messageEvents$ = new Subject<WsEnvelope>();
  /** Emitted on every successful reconnect, after re-subscribing. */
  readonly reconnected$ = new Subject<void>();

  private client: Client | null = null;
  private subscriptions: StompSubscription[] = [];
  private workspaceId: string | null = null;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private typingPruneTimer: ReturnType<typeof setInterval> | null = null;
  private presenceHeartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private intentionalDisconnect = false;
  private refreshedForAuthError = false;
  private seenEventIds = new Set<string>();
  private typingExpiry = new Map<string, Map<string, number>>();

  get isConnected(): boolean {
    return this.status() === 'connected';
  }

  connect(workspaceId: string): void {
    if (this.workspaceId === workspaceId && this.client !== null && this.status() !== 'offline') {
      return;
    }
    this.disconnect();
    this.workspaceId = workspaceId;
    this.reconnectAttempt = 0;
    this.refreshedForAuthError = false;
    this.intentionalDisconnect = false;
    void this.connectWithToken();
  }

  disconnect(): void {
    this.intentionalDisconnect = true;
    this.clearReconnectTimer();
    this.clearTypingPruneTimer();
    this.clearPresenceHeartbeatTimer();
    this.unsubscribeAll();
    this.typing.set({});
    this.typingExpiry.clear();
    this.presence.set({});
    const client = this.client;
    this.client = null;
    this.workspaceId = null;
    this.status.set('offline');
    if (client) {
      void client.deactivate().catch(() => undefined);
    }
  }

  /**
   * Sends a chat message. Returns the fresh clientMessageId so the caller
   * can match the server echo to its optimistic message. Throws when not
   * connected; the caller surfaces that instead of silently dropping the send.
   */
  sendMessage(channelId: string, body: string): string {
    const workspaceId = this.requireWorkspace();
    const client = this.requireClient();
    const clientMessageId = newClientMessageId();
    client.publish({
      destination: `/app/workspaces/${workspaceId}/chat`,
      body: JSON.stringify({ channelId, clientMessageId, body }),
    });
    return clientMessageId;
  }

  sendTyping(channelId: string, typing: boolean): void {
    if (!this.isConnected || this.workspaceId === null) {
      return;
    }
    this.requireClient().publish({
      destination: `/app/workspaces/${this.workspaceId}/typing`,
      body: JSON.stringify({ channelId, typing }),
    });
  }

  sendAck(messageId: string): void {
    if (!this.isConnected || this.workspaceId === null) {
      return;
    }
    this.requireClient().publish({
      destination: `/app/workspaces/${this.workspaceId}/ack`,
      body: JSON.stringify({ messageId }),
    });
  }

  /**
   * Presence heartbeat ping. One ping per connection: the server keys
   * presence by STOMP session, so closing one tab never takes the user
   * offline while another tab stays open. No-op while disconnected; the
   * interval started on connect sends immediately and then every
   * PRESENCE_HEARTBEAT_MS while the workspace view is open.
   */
  sendHeartbeat(): void {
    if (!this.isConnected || this.workspaceId === null) {
      return;
    }
    this.requireClient().publish({
      destination: `/app/workspaces/${this.workspaceId}/presence`,
      body: JSON.stringify({}),
    });
  }

  /**
   * Seeds the presence signal from a REST GET /presence snapshot — the
   * recovery source after a (re)connect, since the topic only carries
   * changes, not the current roster.
   */
  applyPresenceSnapshot(members: PresenceMember[]): void {
    const next: Record<string, PresenceState> = {};
    for (const member of members) {
      next[member.userId] = { status: member.status, lastSeenAt: member.lastSeenAt };
    }
    this.presence.set(next);
  }

  private async connectWithToken(): Promise<void> {
    const workspaceId = this.workspaceId;
    if (workspaceId === null || this.intentionalDisconnect) {
      return;
    }
    let token = this.auth.token;
    if (token === null) {
      try {
        const res = await firstValueFrom(this.auth.refresh());
        token = res.accessToken;
      } catch {
        this.status.set('offline');
        return;
      }
    }
    if (this.workspaceId !== workspaceId || this.intentionalDisconnect) {
      return;
    }
    const client = this.createClient();
    client.webSocketFactory = () => {
      const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
      return new WebSocket(`${scheme}://${location.host}/ws`);
    };
    client.connectHeaders = { Authorization: `Bearer ${token}` };
    // Manual backoff below; the library's own reconnect stays off.
    client.reconnectDelay = 0;
    client.heartbeatIncoming = 10000;
    client.heartbeatOutgoing = 10000;
    client.onConnect = () => this.handleConnect();
    client.onStompError = (frame) => void this.handleStompError(frame);
    client.onWebSocketClose = () => this.handleSocketClose();
    this.client = client;
    client.activate();
  }

  private handleConnect(): void {
    if (this.workspaceId === null || this.intentionalDisconnect) {
      return;
    }
    const wasReconnect = this.reconnectAttempt > 0;
    this.reconnectAttempt = 0;
    this.status.set('connected');
    this.subscribeAll();
    this.startTypingPruneTimer();
    this.startPresenceHeartbeat();
    if (wasReconnect) {
      this.reconnected$.next();
    }
  }

  private async handleStompError(frame: { headers?: Record<string, string>; body?: string }): Promise<void> {
    const detail = `${frame.headers?.['message'] ?? ''} ${frame.body ?? ''}`;
    const authFailure = /unauthor|401|expired/i.test(detail);
    if (authFailure && !this.refreshedForAuthError) {
      this.refreshedForAuthError = true;
      try {
        await firstValueFrom(this.auth.refresh());
        this.teardownClientOnly();
        this.reconnectAttempt = 0;
        await this.connectWithToken();
        return;
      } catch {
        this.status.set('offline');
        return;
      }
    }
    this.scheduleReconnect();
  }

  private handleSocketClose(): void {
    if (this.intentionalDisconnect) {
      return;
    }
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.intentionalDisconnect || this.workspaceId === null) {
      return;
    }
    this.teardownClientOnly();
    this.status.set('reconnecting');
    const delay = computeBackoffDelay(this.reconnectAttempt);
    this.reconnectAttempt += 1;
    this.clearReconnectTimer();
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      void this.connectWithToken();
    }, delay);
  }

  private subscribeAll(): void {
    const workspaceId = this.workspaceId;
    const client = this.client;
    if (workspaceId === null || client === null) {
      return;
    }
    this.unsubscribeAll();
    this.subscriptions.push(
      client.subscribe(`/topic/workspaces/${workspaceId}/messages`, (message) =>
        this.handleMessageFrame(message),
      ),
      client.subscribe(`/topic/workspaces/${workspaceId}/typing`, (message) =>
        this.handleTypingFrame(message),
      ),
      client.subscribe(`/topic/workspaces/${workspaceId}/presence`, (message) =>
        this.handlePresenceFrame(message),
      ),
    );
  }

  private handleMessageFrame(frame: IMessage): void {
    let envelope: WsEnvelope;
    try {
      envelope = JSON.parse(frame.body) as WsEnvelope;
    } catch {
      return;
    }
    if (!envelope.eventId || this.seenEventIds.has(envelope.eventId)) {
      return;
    }
    this.seenEventIds.add(envelope.eventId);
    if (this.seenEventIds.size > 2000) {
      this.seenEventIds.clear();
    }
    this.messageEvents$.next(envelope);
  }

  private handleTypingFrame(frame: IMessage): void {
    let envelope: WsEnvelope<TypingPayload>;
    try {
      envelope = JSON.parse(frame.body) as WsEnvelope<TypingPayload>;
    } catch {
      return;
    }
    if (envelope.eventId) {
      if (this.seenEventIds.has(envelope.eventId)) {
        return;
      }
      this.seenEventIds.add(envelope.eventId);
    }
    const payload = envelope.payload;
    if (!payload?.channelId || !payload.userId) {
      return;
    }
    const stopped = envelope.eventType === 'TYPING_STOPPED' || payload.typing === false;
    if (stopped) {
      this.removeTypingUser(payload.channelId, payload.userId);
    } else {
      this.addTypingUser(payload.channelId, payload.userId, payload.displayName);
    }
  }

  private handlePresenceFrame(frame: IMessage): void {
    let envelope: WsEnvelope<PresenceChangedPayload>;
    try {
      envelope = JSON.parse(frame.body) as WsEnvelope<PresenceChangedPayload>;
    } catch {
      return;
    }
    if (envelope.eventType !== 'PRESENCE_CHANGED') {
      return;
    }
    if (envelope.eventId) {
      if (this.seenEventIds.has(envelope.eventId)) {
        return;
      }
      this.seenEventIds.add(envelope.eventId);
    }
    const payload = envelope.payload;
    if (!payload?.userId || (payload.status !== 'online' && payload.status !== 'offline')) {
      return;
    }
    this.presence.update((current) => ({
      ...current,
      [payload.userId]: { status: payload.status, lastSeenAt: payload.lastSeenAt ?? null },
    }));
  }

  private addTypingUser(channelId: string, userId: string, displayName: string): void {
    let channelExpiry = this.typingExpiry.get(channelId);
    if (!channelExpiry) {
      channelExpiry = new Map();
      this.typingExpiry.set(channelId, channelExpiry);
    }
    channelExpiry.set(userId, Date.now() + TYPING_TIMEOUT_MS);
    this.typing.update((current) => {
      const users = [...(current[channelId] ?? [])];
      const existing = users.find((user) => user.userId === userId);
      if (existing) {
        existing.displayName = displayName;
      } else {
        users.push({ userId, displayName });
      }
      return { ...current, [channelId]: users };
    });
  }

  private removeTypingUser(channelId: string, userId: string): void {
    this.typingExpiry.get(channelId)?.delete(userId);
    this.typing.update((current) => {
      const users = (current[channelId] ?? []).filter((user) => user.userId !== userId);
      const next = { ...current };
      if (users.length === 0) {
        delete next[channelId];
      } else {
        next[channelId] = users;
      }
      return next;
    });
  }

  private startPresenceHeartbeat(): void {
    this.clearPresenceHeartbeatTimer();
    // Mark this connection online immediately; the interval keeps the
    // server-side TTL refreshed while the workspace view stays open.
    this.sendHeartbeat();
    this.presenceHeartbeatTimer = setInterval(() => this.sendHeartbeat(), PRESENCE_HEARTBEAT_MS);
  }

  private clearPresenceHeartbeatTimer(): void {
    if (this.presenceHeartbeatTimer !== null) {
      clearInterval(this.presenceHeartbeatTimer);
      this.presenceHeartbeatTimer = null;
    }
  }

  private startTypingPruneTimer(): void {
    this.clearTypingPruneTimer();
    this.typingPruneTimer = setInterval(() => {
      const now = Date.now();
      let changed = false;
      for (const [channelId, expiry] of this.typingExpiry) {
        for (const [userId, expiresAt] of expiry) {
          if (expiresAt <= now) {
            expiry.delete(userId);
            changed = true;
          }
        }
        if (expiry.size === 0) {
          this.typingExpiry.delete(channelId);
        }
      }
      if (changed) {
        this.typing.update((current) => {
          const next: Record<string, TypingUser[]> = {};
          for (const [channelId, users] of Object.entries(current)) {
            const alive = users.filter((user) => this.typingExpiry.get(channelId)?.has(user.userId));
            if (alive.length > 0) {
              next[channelId] = alive;
            }
          }
          return next;
        });
      }
    }, 1000);
  }

  private unsubscribeAll(): void {
    for (const subscription of this.subscriptions) {
      try {
        subscription.unsubscribe();
      } catch {
        // Already unsubscribed server-side; nothing to do.
      }
    }
    this.subscriptions = [];
  }

  /** Drops the client without touching reconnect state or the status signal. */
  private teardownClientOnly(): void {
    this.unsubscribeAll();
    this.clearPresenceHeartbeatTimer();
    const client = this.client;
    this.client = null;
    if (client) {
      void client.deactivate().catch(() => undefined);
    }
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private clearTypingPruneTimer(): void {
    if (this.typingPruneTimer !== null) {
      clearInterval(this.typingPruneTimer);
      this.typingPruneTimer = null;
    }
  }

  private requireWorkspace(): string {
    if (this.workspaceId === null) {
      throw new Error('Not connected to a workspace');
    }
    return this.workspaceId;
  }

  private requireClient(): Client {
    if (this.client === null || !this.isConnected) {
      throw new Error('Not connected to the chat server');
    }
    return this.client;
  }
}
