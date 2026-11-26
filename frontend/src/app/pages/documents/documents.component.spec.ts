import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { DocumentService } from '../../core/services/document.service';
import { WorkspaceService } from '../../core/services/workspace.service';
import { SyncDocument } from '../../core/models/document.model';
import { DocumentsComponent } from './documents.component';

const docs: SyncDocument[] = [
  {
    id: 'doc-1',
    workspaceId: 'ws-1',
    title: 'Launch plan',
    createdBy: 'u-1',
    version: 0,
    createdAt: '2026-11-26T00:00:00Z',
    updatedAt: '2026-11-26T00:00:00Z',
  },
  {
    id: 'doc-2',
    workspaceId: 'ws-1',
    title: 'Meeting notes',
    createdBy: 'u-2',
    version: 3,
    createdAt: '2026-11-26T00:00:00Z',
    updatedAt: '2026-11-26T01:00:00Z',
  },
];

describe('DocumentsComponent', () => {
  async function setup(list: SyncDocument[] = docs, listError = false) {
    const documentsMock = {
      listDocuments: vi.fn(() =>
        listError ? throwError(() => new Error('down')) : of({ documents: list }),
      ),
      createDocument: vi.fn((_: string, title: string) => of({ ...docs[0], id: 'doc-3', title })),
      renameDocument: vi.fn((id: string, title: string) =>
        of({ ...docs.find((doc) => doc.id === id), title, version: 1 } as SyncDocument),
      ),
      deleteDocument: vi.fn(() => of(undefined)),
    };
    await TestBed.configureTestingModule({
      imports: [DocumentsComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: (key: string) => (key === 'id' ? 'ws-1' : null) } } },
        },
        { provide: DocumentService, useValue: documentsMock },
        {
          provide: WorkspaceService,
          useValue: {
            get: () =>
              of({
                id: 'ws-1',
                name: 'Alpha Team',
                description: null,
                ownerId: 'u-1',
                role: 'OWNER',
                createdAt: '',
                updatedAt: '',
              }),
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(DocumentsComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    return { fixture, documentsMock };
  }

  function text(fixture: ReturnType<typeof TestBed.createComponent>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('lists the workspace documents with open links', async () => {
    const { fixture } = await setup();
    expect(text(fixture)).toContain('Alpha Team');
    expect(text(fixture)).toContain('Launch plan');
    expect(text(fixture)).toContain('Meeting notes');
    const links = [...fixture.nativeElement.querySelectorAll('.document-link')].map(
      (link: Element) => link.getAttribute('href'),
    );
    expect(links).toEqual([
      '/workspaces/ws-1/documents/doc-1',
      '/workspaces/ws-1/documents/doc-2',
    ]);
  });

  it('shows the empty state when there are no documents', async () => {
    const { fixture } = await setup([]);
    expect(text(fixture)).toContain('No documents yet');
  });

  it('shows an error when the list fails to load', async () => {
    const { fixture } = await setup(docs, true);
    expect(text(fixture)).toContain('Could not load the documents');
  });

  it('creates a document and adds it to the list', async () => {
    const { fixture, documentsMock } = await setup();
    const newButton = [...fixture.nativeElement.querySelectorAll('button')].find(
      (button: Element) => button.textContent?.includes('New document'),
    ) as HTMLButtonElement;
    newButton.click();
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('.create-card input') as HTMLInputElement;
    input.value = 'Roadmap';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.create-card form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(documentsMock.createDocument).toHaveBeenCalledWith('ws-1', 'Roadmap');
    expect(text(fixture)).toContain('Roadmap');
  });

  it('renames a document inline', async () => {
    const { fixture, documentsMock } = await setup();
    const renameButton = [...fixture.nativeElement.querySelectorAll('.document-list button')].find(
      (button: Element) => button.textContent?.trim() === 'Rename',
    ) as HTMLButtonElement;
    renameButton.click();
    fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('.rename-form input') as HTMLInputElement;
    expect(input).not.toBeNull();
    input.value = 'Launch plan v2';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.rename-form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(documentsMock.renameDocument).toHaveBeenCalledWith('doc-1', 'Launch plan v2');
    expect(text(fixture)).toContain('Launch plan v2');
  });

  it('requires a confirm click before deleting', async () => {
    const { fixture, documentsMock } = await setup();
    const deleteButton = [...fixture.nativeElement.querySelectorAll('.document-list button')].find(
      (button: Element) => button.textContent?.trim() === 'Delete',
    ) as HTMLButtonElement;
    deleteButton.click();
    fixture.detectChanges();
    expect(documentsMock.deleteDocument).not.toHaveBeenCalled();
    expect(deleteButton.textContent?.trim()).toBe('Confirm delete');
    deleteButton.click();
    fixture.detectChanges();
    expect(documentsMock.deleteDocument).toHaveBeenCalledWith('doc-1');
    expect(text(fixture)).not.toContain('Launch plan');
  });
});
