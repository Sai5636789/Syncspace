import { Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Subscription } from 'rxjs';
import { ChatService } from '../../core/services/chat.service';
import { ChatStompService } from '../../core/services/chat-stomp.service';
import { AuthService } from '../../core/services/auth.service';
import { PresenceService, formatLastSeen } from '../../core/services/presence.service';
import { WorkspaceService } from '../../core/services/workspace.service';
import {
  Channel,
  ChatMessage,
  ChatMessageView,
  MessageCreatedPayload,
  MessageDeletedPayload,
  MessageUpdatedPayload,
  WsEnvelope,
} from '../../core/models/chat.model';
import { PresenceMember } from '../../core/models/presence.model';

/**
 * Team Chat for one workspace: channel sidebar, live message list, composer.
 *
 * Messages arrive over STOMP and are recovered from REST after a reconnect;
 * the list is merged by message id and optimistic sends are matched to the
 * server echo by clientMessageId. Own messages can be edited or deleted
 * inline; unread badges clear when a channel is opened or a message lands
 * in the open channel. The sidebar member list shows live presence: the
 * roster is seeded from GET /presence and updated by PRESENCE_CHANGED
 * envelopes while this view is open (heartbeats run for the lifetime of
 * the STOMP connection).
 */
@Component({
  selector: 'app-chat',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatBadgeModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss',
})
export class ChatComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly chat = inject(ChatService);
  private readonly stomp = inject(ChatStompService);
  private readonly auth = inject(AuthService);
  private readonly presenceService = inject(PresenceService);
  private readonly workspaces = inject(WorkspaceService);
  private readonly fb = inject(FormBuilder);

  protected readonly stompStatus = this.stomp.status;
  protected readonly typing = this.stomp.typing;

  protected readonly workspaceId = signal('');
  protected readonly workspaceName = signal('');
  protected readonly channels = signal<Channel[]>([]);
  protected readonly members = signal<PresenceMember[]>([]);
  /**
   * Member roster with live presence overlaid: the REST snapshot seeds
   * it, and PRESENCE_CHANGED envelopes (via the stomp presence signal)
   * update each member's status without another round trip.
   */
  protected readonly displayMembers = computed(() => {
    const live = this.stomp.presence();
    return this.members().map((member) => {
      const state = live[member.userId];
      return state === undefined
        ? member
        : { ...member, status: state.status, lastSeenAt: state.lastSeenAt };
    });
  });
  protected readonly selectedChannel = signal<Channel | null>(null);
  protected readonly messages = signal<ChatMessageView[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly sending = signal(false);
  protected readonly creatingChannel = signal(false);
  protected readonly showCreateChannel = signal(false);
  protected readonly editingId = signal<string | null>(null);
  protected readonly confirmDeleteId = signal<string | null>(null);

  protected readonly composer = this.fb.nonNullable.group({
    body: ['', [Validators.required, Validators.maxLength(4000)]],
  });
  protected readonly createChannelForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(80)]],
    description: [''],
  });
  protected readonly editForm = this.fb.nonNullable.group({
    body: ['', [Validators.required, Validators.maxLength(4000)]],
  });

  @ViewChild('messageList') private messageList?: ElementRef<HTMLElement>;

  private subscriptions = new Subscription();
  private nearBottom = true;
  private typingSentAt = 0;
  private typingStopTimer: ReturnType<typeof setTimeout> | null = null;
  private typingActive = false;

  get currentUserId(): string | null {
    return this.auth.currentUser()?.id ?? null;
  }

  ngOnInit(): void {
    const workspaceId = this.route.snapshot.paramMap.get('id');
    if (!workspaceId) {
      this.error.set('No workspace selected.');
      this.loading.set(false);
      return;
    }
    this.workspaceId.set(workspaceId);
    this.workspaces.get(workspaceId).subscribe({
      next: (workspace) => this.workspaceName.set(workspace.name),
      error: () => this.workspaceName.set('Team Chat'),
    });
    this.loadChannels();
    this.loadMembers();
    this.stomp.connect(workspaceId);
    this.subscriptions.add(
      this.stomp.messageEvents$.subscribe((envelope) => this.handleEnvelope(envelope)),
    );
    // Missed events recover from REST after a reconnect.
    this.subscriptions.add(
      this.stomp.reconnected$.subscribe(() => {
        const channel = this.selectedChannel();
        if (channel) {
          this.loadMessages(channel, false);
        }
        this.loadChannels();
        this.loadMembers();
      }),
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.stopTyping();
    this.stomp.disconnect();
  }

  /** Typing users in the open channel, excluding the current user. */
  protected typingNames(): string[] {
    const channel = this.selectedChannel();
    if (!channel) {
      return [];
    }
    const me = this.currentUserId;
    return (this.typing()[channel.id] ?? [])
      .filter((user) => user.userId !== me)
      .map((user) => user.displayName);
  }

  protected typingText(): string {
    const names = this.typingNames();
    if (names.length === 0) {
      return '';
    }
    if (names.length === 1) {
      return `${names[0]} is typing…`;
    }
    if (names.length === 2) {
      return `${names[0]} and ${names[1]} are typing…`;
    }
    return `${names[0]}, ${names[1]} and ${names.length - 2} more are typing…`;
  }

  protected selectChannel(channel: Channel): void {
    if (this.selectedChannel()?.id === channel.id) {
      return;
    }
    this.selectedChannel.set(channel);
    this.messages.set([]);
    this.editingId.set(null);
    this.confirmDeleteId.set(null);
    this.loadMessages(channel, true);
    this.markRead(channel);
  }

  protected createChannel(): void {
    if (this.createChannelForm.invalid) {
      this.createChannelForm.markAllAsTouched();
      return;
    }
    const { name, description } = this.createChannelForm.getRawValue();
    this.creatingChannel.set(true);
    this.chat.createChannel(this.workspaceId(), name.trim(), description.trim() || null).subscribe({
      next: (channel) => {
        this.creatingChannel.set(false);
        this.createChannelForm.reset();
        this.showCreateChannel.set(false);
        this.channels.update((list) => [...list, channel]);
        this.selectChannel(channel);
      },
      error: () => {
        this.creatingChannel.set(false);
        this.error.set('Could not create the channel. Try again.');
      },
    });
  }

  protected sendMessage(): void {
    const channel = this.selectedChannel();
    if (!channel || this.composer.invalid || this.sending()) {
      this.composer.markAllAsTouched();
      return;
    }
    const body = this.composer.getRawValue().body.trim();
    if (body === '') {
      return;
    }
    this.stopTyping();
    this.sending.set(true);
    let clientMessageId: string;
    try {
      clientMessageId = this.stomp.sendMessage(channel.id, body);
    } catch {
      this.sending.set(false);
      this.error.set('Not connected to the chat server. The connection is retrying — try again in a moment.');
      return;
    }
    const me = this.auth.currentUser();
    const optimistic: ChatMessageView = {
      id: `local-${clientMessageId}`,
      channelId: channel.id,
      authorId: me?.id ?? '',
      authorDisplayName: me?.displayName ?? 'You',
      body,
      clientMessageId,
      createdAt: new Date().toISOString(),
      pending: true,
    };
    this.messages.update((list) => [...list, optimistic]);
    this.composer.reset();
    this.sending.set(false);
    this.scrollToBottom();
  }

  protected onComposerInput(): void {
    const channel = this.selectedChannel();
    if (!channel) {
      return;
    }
    const now = Date.now();
    if (!this.typingActive || now - this.typingSentAt > 2000) {
      this.stomp.sendTyping(channel.id, true);
      this.typingActive = true;
      this.typingSentAt = now;
    }
    if (this.typingStopTimer !== null) {
      clearTimeout(this.typingStopTimer);
    }
    this.typingStopTimer = setTimeout(() => this.stopTyping(), 2000);
  }

  protected startEdit(message: ChatMessageView): void {
    this.editingId.set(message.id);
    this.editForm.setValue({ body: message.body ?? '' });
  }

  protected cancelEdit(): void {
    this.editingId.set(null);
  }

  protected saveEdit(message: ChatMessageView): void {
    if (this.editForm.invalid) {
      this.editForm.markAllAsTouched();
      return;
    }
    const body = this.editForm.getRawValue().body.trim();
    if (body === '' || body === message.body) {
      this.editingId.set(null);
      return;
    }
    this.chat.editMessage(message.id, body).subscribe({
      next: (updated) => {
        this.editingId.set(null);
        this.messages.update((list) => list.map((item) => (item.id === updated.id ? updated : item)));
      },
      error: () => this.error.set('Could not save the edit. Try again.'),
    });
  }

  protected deleteMessage(message: ChatMessageView): void {
    if (this.confirmDeleteId() !== message.id) {
      // First click arms the confirm; second click deletes.
      this.confirmDeleteId.set(message.id);
      return;
    }
    this.confirmDeleteId.set(null);
    this.chat.deleteMessage(message.id).subscribe({
      next: () => {
        this.messages.update((list) =>
          list.map((item) =>
            item.id === message.id ? { ...item, body: null, deletedAt: new Date().toISOString() } : item,
          ),
        );
      },
      error: () => this.error.set('Could not delete the message. Try again.'),
    });
  }

  protected retryConnection(): void {
    this.error.set(null);
    this.stomp.connect(this.workspaceId());
  }

  protected onMessagesScroll(): void {
    const el = this.messageList?.nativeElement;
    if (!el) {
      return;
    }
    this.nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
  }

  protected isOwn(message: ChatMessageView): boolean {
    return this.currentUserId !== null && message.authorId === this.currentUserId;
  }

  /** "Online" or "Last seen …" for a member — always real presence state, never hardcoded. */
  protected presenceLabel(member: PresenceMember): string {
    return member.status === 'online' ? 'Online' : formatLastSeen(member.lastSeenAt);
  }

  private loadMembers(): void {
    this.presenceService.listPresence(this.workspaceId()).subscribe({
      next: (members) => {
        this.members.set(members);
        this.stomp.applyPresenceSnapshot(members);
      },
      error: () => {
        // Presence read failed (backend down, stale token): fall back to
        // the plain member roster, all shown offline with no last-seen.
        this.workspaces.listMembers(this.workspaceId()).subscribe({
          next: (roster) =>
            this.members.set(
              roster.map((member) => ({
                userId: member.userId,
                email: member.email,
                displayName: member.displayName,
                role: member.role,
                joinedAt: member.joinedAt,
                status: 'offline' as const,
                lastSeenAt: null,
              })),
            ),
          error: () => this.members.set([]),
        });
      },
    });
  }

  protected displayName(message: ChatMessageView): string {
    if (this.isOwn(message)) {
      return 'You';
    }
    return message.authorDisplayName ?? 'Unknown user';
  }

  private loadChannels(): void {
    this.chat.listChannels(this.workspaceId()).subscribe({
      next: (channels) => {
        this.channels.set(channels);
        this.loading.set(false);
        if (this.selectedChannel() === null && channels.length > 0) {
          const general = channels.find((channel) => channel.name.toLowerCase() === 'general');
          this.selectChannel(general ?? channels[0]);
        }
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not load the channels. Is the backend running?');
      },
    });
  }

  private loadMessages(channel: Channel, scroll: boolean): void {
    this.chat.listMessages(channel.id, 0, 50).subscribe({
      next: (page) => {
        this.mergeMessages(page.content, channel.id);
        if (scroll) {
          this.scrollToBottom();
        }
      },
      error: () => this.error.set('Could not load the messages for this channel.'),
    });
  }

  /** Merges REST history into the list: by id, replacing an optimistic send by clientMessageId. */
  private mergeMessages(incoming: ChatMessage[], channelId: string): void {
    this.messages.update((list) => {
      const merged = [...list];
      for (const message of incoming) {
        if (message.channelId !== channelId) {
          continue;
        }
        const byId = merged.findIndex((item) => item.id === message.id);
        if (byId >= 0) {
          merged[byId] = message;
          continue;
        }
        if (message.clientMessageId) {
          const byClientId = merged.findIndex((item) => item.clientMessageId === message.clientMessageId);
          if (byClientId >= 0) {
            merged[byClientId] = message;
            continue;
          }
        }
        merged.push(message);
      }
      merged.sort((a, b) => a.createdAt.localeCompare(b.createdAt));
      return merged;
    });
  }

  private handleEnvelope(envelope: WsEnvelope): void {
    const channel = this.selectedChannel();
    switch (envelope.eventType) {
      case 'MESSAGE_CREATED': {
        const payload = envelope.payload as MessageCreatedPayload;
        if (!payload?.message) {
          return;
        }
        if (payload.channelId === channel?.id) {
          this.mergeMessages([payload.message], channel.id);
          this.stomp.sendAck(payload.message.id);
          this.markRead(channel);
          if (this.nearBottom || payload.message.authorId === this.currentUserId) {
            this.scrollToBottom();
          }
        } else {
          this.bumpUnread(payload.channelId);
        }
        break;
      }
      case 'MESSAGE_UPDATED': {
        const payload = envelope.payload as MessageUpdatedPayload;
        if (!payload?.message) {
          return;
        }
        if (payload.channelId === channel?.id) {
          this.messages.update((list) =>
            list.map((item) => (item.id === payload.message.id ? payload.message : item)),
          );
        }
        break;
      }
      case 'MESSAGE_DELETED': {
        const payload = envelope.payload as MessageDeletedPayload;
        const messageId = payload?.messageId ?? (payload as unknown as { message?: ChatMessage })?.message?.id;
        if (!messageId) {
          return;
        }
        if (payload.channelId === channel?.id || channel === null) {
          this.messages.update((list) =>
            list.map((item) =>
              item.id === messageId ? { ...item, body: null, deletedAt: new Date().toISOString() } : item,
            ),
          );
        }
        break;
      }
      default:
        break;
    }
  }

  private bumpUnread(channelId: string): void {
    this.channels.update((list) =>
      list.map((item) =>
        item.id === channelId ? { ...item, unreadCount: item.unreadCount + 1 } : item,
      ),
    );
  }

  private markRead(channel: Channel): void {
    this.chat.markRead(channel.id).subscribe({
      next: () => {
        this.channels.update((list) =>
          list.map((item) => (item.id === channel.id ? { ...item, unreadCount: 0 } : item)),
        );
      },
      error: () => undefined,
    });
  }

  private stopTyping(): void {
    if (this.typingStopTimer !== null) {
      clearTimeout(this.typingStopTimer);
      this.typingStopTimer = null;
    }
    if (this.typingActive) {
      const channel = this.selectedChannel();
      if (channel) {
        this.stomp.sendTyping(channel.id, false);
      }
      this.typingActive = false;
    }
  }

  private scrollToBottom(): void {
    setTimeout(() => {
      const el = this.messageList?.nativeElement;
      if (el) {
        el.scrollTop = el.scrollHeight;
        this.nearBottom = true;
      }
    }, 0);
  }
}
