/** Presence types. Shapes follow docs/api-contracts.md (GET /workspaces/{id}/presence) and docs/websocket-events.md (PRESENCE_CHANGED). */

export type PresenceStatus = 'online' | 'offline';

export interface PresenceState {
  status: PresenceStatus;
  lastSeenAt: string | null;
}

/** One workspace member with live presence state, as returned by GET /presence. */
export interface PresenceMember {
  userId: string;
  email: string;
  displayName: string;
  role: string;
  joinedAt: string;
  status: PresenceStatus;
  lastSeenAt: string | null;
}

export interface PresenceChangedPayload {
  userId: string;
  status: PresenceStatus;
  lastSeenAt: string;
}
