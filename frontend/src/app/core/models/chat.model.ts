/** Chat domain types. Shapes follow the messaging contract in docs/api-contracts.md. */

export interface Channel {
  id: string;
  name: string;
  description: string | null;
  unreadCount: number;
}

export interface ChatMessage {
  id: string;
  channelId: string;
  authorId: string;
  authorDisplayName?: string | null;
  /** Null once the message has been soft-deleted. */
  body: string | null;
  clientMessageId: string | null;
  createdAt: string;
  editedAt?: string | null;
  deletedAt?: string | null;
}

/** Local view of a message; `pending` marks an optimistic message not yet echoed by the server. */
export interface ChatMessageView extends ChatMessage {
  pending?: boolean;
}

export interface MessagePage {
  content: ChatMessage[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** STOMP envelope from docs/websocket-events.md. */
export interface WsEnvelope<T = unknown> {
  eventId: string;
  eventType: string;
  workspaceId: string | null;
  resourceId: string | null;
  actorId: string | null;
  timestamp: string;
  version: number;
  payload: T;
}

export interface MessageCreatedPayload {
  channelId: string;
  message: ChatMessage;
}

export interface MessageUpdatedPayload {
  channelId: string;
  message: ChatMessage;
}

export interface MessageDeletedPayload {
  channelId: string;
  messageId: string;
}

export interface TypingPayload {
  channelId: string;
  userId: string;
  displayName: string;
  /** Present when the server echoes a boolean typing flag instead of STARTED/STOPPED types. */
  typing?: boolean;
}

export interface TypingUser {
  userId: string;
  displayName: string;
}
