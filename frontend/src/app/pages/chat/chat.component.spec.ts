import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AuthService } from '../../core/services/auth.service';
import { ChatService } from '../../core/services/chat.service';
import { ChatStompService } from '../../core/services/chat-stomp.service';
import { WorkspaceService } from '../../core/services/workspace.service';
import { Channel, ChatMessage, TypingUser, WsEnvelope } from '../../core/models/chat.model';
import { ChatComponent } from './chat.component';

const channels: Channel[] = [
  { id: 'ch-1', name: 'general', description: null, unreadCount: 2 },
  { id: 'ch-2', name: 'random', description: null, unreadCount: 3 },
];

const messages: ChatMessage[] = [
  {
    id: 'm-1',
    channelId: 'ch-1',
    authorId: 'u-1',
    authorDisplayName: 'Sai',
    body: 'Hello team',
    clientMessageId: 'c-1',
    createdAt: '2026-11-25T00:00:00Z',
  },
  {
    id: 'm-2',
    channelId: 'ch-1',
    authorId: 'u-2',
    authorDisplayName: 'Ana',
    body: 'Hi Sai',
    clientMessageId: 'c-2',
    createdAt: '2026-11-25T00:01:00Z',
  },
];

function envelope(eventId: string, eventType: string, payload: unknown): WsEnvelope {
  return {
    eventId,
    eventType,
    workspaceId: 'ws-1',
    resourceId: null,
    actorId: 'u-2',
    timestamp: '2026-11-25T00:00:00Z',
    version: 1,
    payload,
  };
}

describe('ChatComponent', () => {
  async function setup() {
    const messageEvents$ = new Subject<WsEnvelope>();
    const reconnected$ = new Subject<void>();
    const typingSignal = signal<Readonly<Record<string, TypingUser[]>>>({});
    const chatMock = {
      listChannels: vi.fn(() => of(channels)),
      createChannel: vi.fn((_: string, name: string) =>
        of({ id: 'ch-3', name, description: null, unreadCount: 0 }),
      ),
      listMessages: vi.fn(() =>
        of({ content: messages, page: 0, size: 50, totalElements: 2, totalPages: 1 }),
      ),
      markRead: vi.fn(() => of(undefined)),
      editMessage: vi.fn((id: string, body: string) =>
        of({ ...messages[0], id, body, editedAt: '2026-11-25T00:02:00Z' }),
      ),
      deleteMessage: vi.fn(() => of(undefined)),
    };
    const stompMock = {
      status: signal('connected'),
      typing: typingSignal,
      messageEvents$,
      reconnected$,
      connect: vi.fn(),
      disconnect: vi.fn(),
      sendMessage: vi.fn(() => 'client-uuid-1'),
      sendTyping: vi.fn(),
      sendAck: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [ChatComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: (key: string) => (key === 'id' ? 'ws-1' : null) } } },
        },
        {
          provide: AuthService,
          useValue: {
            currentUser: signal({ id: 'u-1', email: 'sai@example.com', displayName: 'Sai' }),
          },
        },
        { provide: ChatService, useValue: chatMock },
        { provide: ChatStompService, useValue: stompMock },
        {
          provide: WorkspaceService,
          useValue: {
            get: () => of({ id: 'ws-1', name: 'Alpha Team', description: null, ownerId: 'u-1', role: 'OWNER', createdAt: '', updatedAt: '' }),
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(ChatComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    return { fixture, chatMock, stompMock, messageEvents$, reconnected$, typingSignal };
  }

  function text(fixture: ReturnType<typeof TestBed.createComponent>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('loads channels, auto-selects general, loads its messages, and marks it read', async () => {
    const { fixture, chatMock, stompMock } = await setup();
    expect(stompMock.connect).toHaveBeenCalledWith('ws-1');
    expect(text(fixture)).toContain('Alpha Team');
    expect(text(fixture)).toContain('# general');
    expect(text(fixture)).toContain('Hello team');
    expect(text(fixture)).toContain('Hi Sai');
    expect(chatMock.listMessages).toHaveBeenCalledWith('ch-1', 0, 50);
    expect(chatMock.markRead).toHaveBeenCalledWith('ch-1');
  });

  it('sends a message and shows the optimistic send', async () => {
    const { fixture, stompMock } = await setup();
    const component = fixture.componentInstance as unknown as {
      composer: { setValue: (v: { body: string }) => void };
      sendMessage: () => void;
    };
    component.composer.setValue({ body: 'hello all' });
    fixture.detectChanges();
    const form = fixture.nativeElement.querySelector('.composer') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    fixture.detectChanges();
    expect(stompMock.sendMessage).toHaveBeenCalledWith('ch-1', 'hello all');
    expect(text(fixture)).toContain('hello all');
    expect(text(fixture)).toContain('Sending…');
  });

  it('merges an incoming message, acks it, and replaces the optimistic send on echo', async () => {
    const { fixture, stompMock, messageEvents$ } = await setup();
    messageEvents$.next(
      envelope('evt-1', 'MESSAGE_CREATED', {
        channelId: 'ch-1',
        message: { ...messages[0], id: 'm-9', body: 'live message', clientMessageId: null },
      }),
    );
    fixture.detectChanges();
    expect(text(fixture)).toContain('live message');
    expect(stompMock.sendAck).toHaveBeenCalledWith('m-9');

    // Echo of the optimistic send: same clientMessageId, real id — no duplicate row.
    const component = fixture.componentInstance as unknown as {
      composer: { setValue: (v: { body: string }) => void };
    };
    component.composer.setValue({ body: 'hello all' });
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.composer') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    messageEvents$.next(
      envelope('evt-2', 'MESSAGE_CREATED', {
        channelId: 'ch-1',
        message: { ...messages[0], id: 'm-10', body: 'hello all', clientMessageId: 'client-uuid-1' },
      }),
    );
    fixture.detectChanges();
    const occurrences = (text(fixture).match(/hello all/g) ?? []).length;
    expect(occurrences).toBe(1);
    expect(text(fixture)).not.toContain('Sending…');
  });

  it('bumps the unread badge for other channels', async () => {
    const { fixture, messageEvents$ } = await setup();
    messageEvents$.next(
      envelope('evt-3', 'MESSAGE_CREATED', {
        channelId: 'ch-2',
        message: { id: 'm-11', channelId: 'ch-2', authorId: 'u-2', body: 'over here', createdAt: '2026-11-25T00:03:00Z' },
      }),
    );
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
      channels: () => Channel[];
    };
    expect(component.channels().find((channel) => channel.id === 'ch-2')?.unreadCount).toBe(4);
  });

  it('shows edit and delete only for own messages and edits inline', async () => {
    const { fixture, chatMock } = await setup();
    const buttons = [...fixture.nativeElement.querySelectorAll('.message-actions button')].map(
      (button: Element) => button.textContent?.trim(),
    );
    expect(buttons).toEqual(['Edit', 'Delete']);

    const editButton = [...fixture.nativeElement.querySelectorAll('.message-actions button')].find(
      (button: Element) => button.textContent?.trim() === 'Edit',
    ) as HTMLButtonElement;
    editButton.click();
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('.edit-form input') as HTMLInputElement;
    expect(input).not.toBeNull();
    input.value = 'Hello team (edited)';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.edit-form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(chatMock.editMessage).toHaveBeenCalledWith('m-1', 'Hello team (edited)');
    expect(text(fixture)).toContain('Hello team (edited)');
  });

  it('requires a confirm click before deleting', async () => {
    const { fixture, chatMock } = await setup();
    const deleteButton = [...fixture.nativeElement.querySelectorAll('.message-actions button')].find(
      (button: Element) => button.textContent?.trim() === 'Delete',
    ) as HTMLButtonElement;
    deleteButton.click();
    fixture.detectChanges();
    expect(chatMock.deleteMessage).not.toHaveBeenCalled();
    expect(deleteButton.textContent?.trim()).toBe('Confirm delete');
    deleteButton.click();
    fixture.detectChanges();
    expect(chatMock.deleteMessage).toHaveBeenCalledWith('m-1');
    expect(text(fixture)).toContain('This message was deleted.');
  });

  it('shows who is typing in the open channel', async () => {
    const { fixture, typingSignal } = await setup();
    typingSignal.set({ 'ch-1': [{ userId: 'u-2', displayName: 'Ana' }] });
    fixture.detectChanges();
    expect(text(fixture)).toContain('Ana is typing…');
  });

  it('creates a channel and switches to it', async () => {
    const { fixture, chatMock } = await setup();
    const newChannelButton = [...fixture.nativeElement.querySelectorAll('.channel-list button')].find(
      (button: Element) => button.textContent?.includes('New channel'),
    ) as HTMLButtonElement;
    newChannelButton.click();
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('.channel-list form input') as HTMLInputElement;
    input.value = 'announcements';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.channel-list form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(chatMock.createChannel).toHaveBeenCalledWith('ws-1', 'announcements', null);
    expect(text(fixture)).toContain('# announcements');
  });

  it('recovers from REST after a reconnect', async () => {
    const { fixture, chatMock, reconnected$ } = await setup();
    const callsBefore = chatMock.listMessages.mock.calls.length;
    reconnected$.next();
    fixture.detectChanges();
    expect(chatMock.listMessages.mock.calls.length).toBeGreaterThan(callsBefore);
  });
});
