import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Channel, ChatMessage, MessagePage } from '../models/chat.model';

/** Channel and message REST calls. The auth interceptor attaches the access token. */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly apiUrl = environment.apiUrl;

  constructor(private readonly http: HttpClient) {}

  listChannels(workspaceId: string): Observable<Channel[]> {
    return this.http.get<Channel[]>(`${this.apiUrl}/workspaces/${workspaceId}/channels`);
  }

  createChannel(workspaceId: string, name: string, description: string | null): Observable<Channel> {
    return this.http.post<Channel>(`${this.apiUrl}/workspaces/${workspaceId}/channels`, {
      name,
      description,
    });
  }

  listMessages(channelId: string, page = 0, size = 50): Observable<MessagePage> {
    return this.http.get<MessagePage>(`${this.apiUrl}/channels/${channelId}/messages`, {
      params: { page, size },
    });
  }

  markRead(channelId: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/channels/${channelId}/read`, {});
  }

  editMessage(messageId: string, body: string): Observable<ChatMessage> {
    return this.http.patch<ChatMessage>(`${this.apiUrl}/messages/${messageId}`, { body });
  }

  deleteMessage(messageId: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/messages/${messageId}`);
  }
}
