import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PresenceMember } from '../models/presence.model';

/**
 * Human-readable "last seen" text for an offline member. Pure function
 * (the `now` parameter exists so tests can pin the time).
 */
export function formatLastSeen(lastSeenAt: string | null, now: Date = new Date()): string {
  if (lastSeenAt === null) {
    return 'Never seen online';
  }
  const seen = new Date(lastSeenAt).getTime();
  if (Number.isNaN(seen)) {
    return 'Last seen unknown';
  }
  const seconds = Math.max(0, Math.floor((now.getTime() - seen) / 1000));
  if (seconds < 60) {
    return 'Last seen just now';
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `Last seen ${minutes} min ago`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `Last seen ${hours} h ago`;
  }
  return `Last seen ${new Date(lastSeenAt).toLocaleDateString()}`;
}

/** Presence REST reads. The live state arrives over STOMP (ChatStompService); this is the recovery source. */
@Injectable({ providedIn: 'root' })
export class PresenceService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = `${environment.apiUrl}/workspaces`;

  listPresence(workspaceId: string): Observable<PresenceMember[]> {
    return this.http.get<PresenceMember[]>(`${this.apiUrl}/${workspaceId}/presence`);
  }
}
