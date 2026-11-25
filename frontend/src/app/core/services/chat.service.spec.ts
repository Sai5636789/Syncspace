import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { ChatService } from './chat.service';

describe('ChatService', () => {
  function setup(): { service: ChatService; http: HttpTestingController } {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    return { service: TestBed.inject(ChatService), http: TestBed.inject(HttpTestingController) };
  }

  it('lists channels for a workspace', () => {
    const { service, http } = setup();
    service.listChannels('ws-1').subscribe((channels) => {
      expect(channels).toEqual([{ id: 'ch-1', name: 'general', description: null, unreadCount: 0 }]);
    });
    const req = http.expectOne('/api/v1/workspaces/ws-1/channels');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 'ch-1', name: 'general', description: null, unreadCount: 0 }]);
    http.verify();
  });

  it('creates a channel with name and description', () => {
    const { service, http } = setup();
    service.createChannel('ws-1', 'random', 'Off-topic').subscribe((channel) => {
      expect(channel.name).toBe('random');
    });
    const req = http.expectOne('/api/v1/workspaces/ws-1/channels');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'random', description: 'Off-topic' });
    req.flush({ id: 'ch-2', name: 'random', description: 'Off-topic', unreadCount: 0 });
    http.verify();
  });

  it('lists messages with page and size params', () => {
    const { service, http } = setup();
    service.listMessages('ch-1', 0, 50).subscribe((page) => {
      expect(page.totalElements).toBe(1);
    });
    const req = http.expectOne('/api/v1/channels/ch-1/messages?page=0&size=50');
    expect(req.request.method).toBe('GET');
    req.flush({ content: [], page: 0, size: 50, totalElements: 1, totalPages: 1 });
    http.verify();
  });

  it('marks a channel read, edits, and deletes a message', () => {
    const { service, http } = setup();
    service.markRead('ch-1').subscribe(() => undefined);
    const readReq = http.expectOne('/api/v1/channels/ch-1/read');
    expect(readReq.request.method).toBe('POST');
    readReq.flush(null);

    service.editMessage('m-1', 'edited').subscribe((message) => {
      expect(message.body).toBe('edited');
    });
    const editReq = http.expectOne('/api/v1/messages/m-1');
    expect(editReq.request.method).toBe('PATCH');
    expect(editReq.request.body).toEqual({ body: 'edited' });
    editReq.flush({ id: 'm-1', channelId: 'ch-1', authorId: 'u-1', body: 'edited', createdAt: '2026-11-25T00:00:00Z' });

    service.deleteMessage('m-1').subscribe(() => undefined);
    const deleteReq = http.expectOne('/api/v1/messages/m-1');
    expect(deleteReq.request.method).toBe('DELETE');
    deleteReq.flush(null);
    http.verify();
  });
});
