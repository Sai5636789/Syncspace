import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER';

export interface Workspace {
  id: string;
  name: string;
  description: string | null;
  ownerId: string;
  role: WorkspaceRole;
  createdAt: string;
  updatedAt: string;
}

export interface WorkspaceMember {
  userId: string;
  email: string;
  displayName: string;
  role: WorkspaceRole;
  joinedAt: string;
}

/** Workspace API calls. Membership and roles are enforced server-side. */
@Injectable({ providedIn: 'root' })
export class WorkspaceService {
  private readonly apiUrl = `${environment.apiUrl}/workspaces`;

  constructor(private readonly http: HttpClient) {}

  listMine(): Observable<Workspace[]> {
    return this.http.get<Workspace[]>(this.apiUrl);
  }

  get(workspaceId: string): Observable<Workspace> {
    return this.http.get<Workspace>(`${this.apiUrl}/${workspaceId}`);
  }

  create(name: string, description: string | null): Observable<Workspace> {
    return this.http.post<Workspace>(this.apiUrl, { name, description });
  }

  listMembers(workspaceId: string): Observable<WorkspaceMember[]> {
    return this.http.get<WorkspaceMember[]>(`${this.apiUrl}/${workspaceId}/members`);
  }

  leave(workspaceId: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/${workspaceId}/leave`, {});
  }
}
