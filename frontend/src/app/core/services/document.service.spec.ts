import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { DocumentService } from './document.service';
import { SyncDocument } from '../models/document.model';

const doc: SyncDocument = {
  id: 'doc-1',
  workspaceId: 'ws-1',
  title: 'Launch plan',
  createdBy: 'u-1',
  version: 0,
  createdAt: '2026-11-26T00:00:00Z',
  updatedAt: '2026-11-26T00:00:00Z',
};

describe('DocumentService', () => {
  function setup(): { service: DocumentService; http: HttpTestingController } {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    return { service: TestBed.inject(DocumentService), http: TestBed.inject(HttpTestingController) };
  }

  it('lists documents for a workspace', () => {
    const { service, http } = setup();
    service.listDocuments('ws-1').subscribe((list) => {
      expect(list.documents).toEqual([doc]);
    });
    const req = http.expectOne('/api/v1/workspaces/ws-1/documents');
    expect(req.request.method).toBe('GET');
    req.flush({ documents: [doc] });
    http.verify();
  });

  it('creates a document with a title', () => {
    const { service, http } = setup();
    service.createDocument('ws-1', 'Launch plan').subscribe((created) => {
      expect(created.title).toBe('Launch plan');
    });
    const req = http.expectOne('/api/v1/workspaces/ws-1/documents');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ title: 'Launch plan' });
    req.flush(doc);
    http.verify();
  });

  it('gets a document by id', () => {
    const { service, http } = setup();
    service.getDocument('doc-1').subscribe((found) => {
      expect(found.id).toBe('doc-1');
    });
    const req = http.expectOne('/api/v1/documents/doc-1');
    expect(req.request.method).toBe('GET');
    req.flush(doc);
    http.verify();
  });

  it('renames a document', () => {
    const { service, http } = setup();
    service.renameDocument('doc-1', 'Launch plan v2').subscribe((renamed) => {
      expect(renamed.title).toBe('Launch plan v2');
      expect(renamed.version).toBe(1);
    });
    const req = http.expectOne('/api/v1/documents/doc-1');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ title: 'Launch plan v2' });
    req.flush({ ...doc, title: 'Launch plan v2', version: 1 });
    http.verify();
  });

  it('deletes a document', () => {
    const { service, http } = setup();
    service.deleteDocument('doc-1').subscribe(() => undefined);
    const req = http.expectOne('/api/v1/documents/doc-1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
    http.verify();
  });
});
