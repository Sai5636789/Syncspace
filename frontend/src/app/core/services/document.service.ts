import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DocumentList, SyncDocument } from '../models/document.model';

/** Document metadata REST calls. The auth interceptor attaches the access token. */
@Injectable({ providedIn: 'root' })
export class DocumentService {
  private readonly apiUrl = environment.apiUrl;

  constructor(private readonly http: HttpClient) {}

  listDocuments(workspaceId: string): Observable<DocumentList> {
    return this.http.get<DocumentList>(`${this.apiUrl}/workspaces/${workspaceId}/documents`);
  }

  createDocument(workspaceId: string, title: string): Observable<SyncDocument> {
    return this.http.post<SyncDocument>(`${this.apiUrl}/workspaces/${workspaceId}/documents`, {
      title,
    });
  }

  getDocument(documentId: string): Observable<SyncDocument> {
    return this.http.get<SyncDocument>(`${this.apiUrl}/documents/${documentId}`);
  }

  renameDocument(documentId: string, title: string): Observable<SyncDocument> {
    return this.http.patch<SyncDocument>(`${this.apiUrl}/documents/${documentId}`, { title });
  }

  deleteDocument(documentId: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/documents/${documentId}`);
  }
}
