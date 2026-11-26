import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { PresenceService, formatLastSeen } from './presence.service';
import { PresenceMember } from '../models/presence.model';

describe('formatLastSeen', () => {
  const now = new Date('2026-11-26T12:00:00Z');

  it('reports never seen when there is no last-seen instant', () => {
    expect(formatLastSeen(null, now)).toBe('Never seen online');
  });

  it('reports just now within a minute', () => {
    expect(formatLastSeen('2026-11-26T11:59:30Z', now)).toBe('Last seen just now');
  });

  it('reports minutes and hours', () => {
    expect(formatLastSeen('2026-11-26T11:45:00Z', now)).toBe('Last seen 15 min ago');
    expect(formatLastSeen('2026-11-26T09:30:00Z', now)).toBe('Last seen 2 h ago');
  });

  it('reports the date for older sightings', () => {
    expect(formatLastSeen('2026-11-20T09:30:00Z', now)).toBe(
      `Last seen ${new Date('2026-11-20T09:30:00Z').toLocaleDateString()}`,
    );
  });
});

describe('PresenceService', () => {
  function setup(): { service: PresenceService; http: HttpTestingController } {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    return { service: TestBed.inject(PresenceService), http: TestBed.inject(HttpTestingController) };
  }

  it('lists presence for a workspace', () => {
    const { service, http } = setup();
    const members: PresenceMember[] = [
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
    ];
    service.listPresence('ws-1').subscribe((result) => {
      expect(result).toEqual(members);
    });
    const req = http.expectOne('/api/v1/workspaces/ws-1/presence');
    expect(req.request.method).toBe('GET');
    req.flush(members);
    http.verify();
  });
});
