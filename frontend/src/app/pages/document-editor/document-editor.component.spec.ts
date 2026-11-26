import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute } from '@angular/router';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as Y from 'yjs';
import { WebsocketProvider } from 'y-websocket';
import { Awareness } from 'y-protocols/awareness';
import { Editor } from '@tiptap/core';
import { AuthService } from '../../core/services/auth.service';
import { DocumentService } from '../../core/services/document.service';
import {
  CollabService,
  CollabSession,
  Collaborator,
  SaveState,
} from '../../core/services/collab.service';
import { SyncDocument } from '../../core/models/document.model';
import { DocumentEditorComponent } from './document-editor.component';

/** Minimal stand-in for WebsocketProvider; only its awareness is real. */
class FakeProvider {
  readonly awareness: Awareness;
  synced = true;
  ws: { readyState: number; bufferedAmount: number } | null = null;
  disconnect = vi.fn();
  destroy = vi.fn();

  private handlers = new Map<string, Set<(...args: unknown[]) => void>>();

  constructor(doc: Y.Doc, awareness: Awareness) {
    this.awareness = awareness;
    void doc;
  }

  on(event: string, cb: (...args: unknown[]) => void): void {
    let set = this.handlers.get(event);
    if (!set) {
      set = new Set();
      this.handlers.set(event, set);
    }
    set.add(cb);
  }

  off(event: string, cb: (...args: unknown[]) => void): void {
    this.handlers.get(event)?.delete(cb);
  }
}

const docMeta: SyncDocument = {
  id: 'doc-1',
  workspaceId: 'ws-1',
  title: 'Launch plan',
  createdBy: 'u-1',
  version: 0,
  createdAt: '2026-11-26T00:00:00Z',
  updatedAt: '2026-11-26T00:00:00Z',
};

describe('DocumentEditorComponent', () => {
  let fakeDoc: Y.Doc | null = null;

  async function setup(options?: {
    getDocumentResult?: SyncDocument | { status: number };
    connectResult?: 'ok' | Error;
    saveState?: SaveState;
    collaborators?: Collaborator[];
    forbidden?: boolean;
  }) {
    const ydoc = new Y.Doc();
    fakeDoc = ydoc;
    const awareness = new Awareness(ydoc);
    const fakeProvider = new FakeProvider(ydoc, awareness);
    const saveStateSignal = signal<SaveState>(options?.saveState ?? 'saved');
    const collaboratorsSignal = signal<Collaborator[]>(options?.collaborators ?? []);
    const forbiddenSignal = signal(options?.forbidden ?? false);
    const statusSignal = signal<'connecting' | 'connected' | 'disconnected'>('connected');
    const fakeSession: CollabSession = {
      doc: ydoc,
      provider: fakeProvider as unknown as WebsocketProvider,
      awareness,
      status: statusSignal,
      saveState: saveStateSignal,
      forbidden: forbiddenSignal,
      collaborators: collaboratorsSignal,
      destroy: vi.fn(() => {
        awareness.destroy();
        ydoc.destroy();
      }),
    };
    const getDocumentResult = options?.getDocumentResult;
    const documentsMock = {
      getDocument: vi.fn(() =>
        getDocumentResult !== undefined && typeof getDocumentResult === 'object' && 'status' in getDocumentResult
          ? throwError(() => new HttpErrorResponse({ status: getDocumentResult.status }))
          : of(getDocumentResult ?? docMeta),
      ),
    };
    const collabMock = {
      connect: vi.fn(() =>
        options?.connectResult instanceof Error
          ? Promise.reject(options.connectResult)
          : Promise.resolve(fakeSession),
      ),
    };
    await TestBed.configureTestingModule({
      imports: [DocumentEditorComponent],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: {
                get: (key: string) => (key === 'id' ? 'ws-1' : key === 'documentId' ? 'doc-1' : null),
              },
            },
          },
        },
        { provide: DocumentService, useValue: documentsMock },
        { provide: CollabService, useValue: collabMock },
        {
          provide: AuthService,
          useValue: {
            currentUser: signal({ id: 'u-1', email: 'sai@example.com', displayName: 'Sai' }),
          },
        },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(DocumentEditorComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    // The component loads metadata and connects over async/await; the
    // session promise resolves on a microtask boundary that whenStable
    // does not wait out in this setup, so flush with a macrotask.
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    await fixture.whenStable();
    return {
      fixture,
      documentsMock,
      collabMock,
      fakeSession,
      saveStateSignal,
      collaboratorsSignal,
      forbiddenSignal,
      statusSignal,
      editor: () =>
        (fixture.componentInstance as unknown as { editor: Editor | null }).editor,
    };
  }

  afterEach(() => {
    fakeDoc = null;
  });

  function text(fixture: ReturnType<typeof TestBed.createComponent>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('loads the document, opens the editor, and shows the save state', async () => {
    const { fixture, collabMock } = await setup();
    expect(collabMock.connect).toHaveBeenCalledWith('doc-1');
    expect(text(fixture)).toContain('Launch plan');
    expect(text(fixture)).toContain('Saved');
    expect(fixture.nativeElement.querySelector('.doc-editor-content')).not.toBeNull();
  });

  it('toggles bold on selected text through the real editor', async () => {
    const { fixture, editor } = await setup();
    const ed = editor();
    expect(ed).not.toBeNull();
    ed?.commands.setContent('<p>hello</p>');
    ed?.commands.selectAll();
    (fixture.nativeElement.querySelector('button[aria-label="Bold"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(ed?.getHTML()).toContain('<strong>hello</strong>');
  });

  it('applies a link to the selected text', async () => {
    const { fixture, editor } = await setup();
    const ed = editor();
    expect(ed).not.toBeNull();
    ed?.commands.setContent('<p>example</p>');
    ed?.commands.selectAll();
    const component = fixture.componentInstance as unknown as {
      linkForm: { setValue: (value: { url: string }) => void };
    };
    component.linkForm.setValue({ url: 'https://example.com' });
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.link-row') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
    expect(ed?.getHTML()).toContain('href="https://example.com"');
  });

  it('shows collaborators from awareness, including the local user', async () => {
    const { fixture } = await setup({
      collaborators: [
        { clientId: 1, userId: 'u-1', name: 'Sai', color: '#d32f2f', isSelf: true },
        { clientId: 2, userId: 'u-2', name: 'Ana', color: '#1976d2', isSelf: false },
      ],
    });
    expect(text(fixture)).toContain('You');
    expect(text(fixture)).toContain('Ana');
  });

  it('reflects the live save state in the badge', async () => {
    const { fixture, saveStateSignal } = await setup({ saveState: 'saving' });
    expect(text(fixture)).toContain('Saving…');
    saveStateSignal.set('offline');
    fixture.detectChanges();
    expect(text(fixture)).toContain('Offline');
  });

  it('shows the no-access state when the collab server closes with 4401', async () => {
    const { fixture, forbiddenSignal, editor } = await setup();
    forbiddenSignal.set(true);
    fixture.detectChanges();
    expect(text(fixture)).toContain("You don't have access to this document");
    expect(editor()?.isEditable).toBe(false);
  });

  it('shows a not-found error when the metadata load fails', async () => {
    const { fixture } = await setup({ getDocumentResult: { status: 404 } });
    expect(text(fixture)).toContain('does not exist or you do not have access');
  });

  it('shows the connect error when the session cannot start', async () => {
    const { fixture } = await setup({ connectResult: new Error('Your session expired. Sign in again.') });
    expect(text(fixture)).toContain('Your session expired');
  });
});
