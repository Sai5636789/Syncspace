import {
  AfterViewInit,
  ChangeDetectorRef,
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  ViewChild,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import Underline from '@tiptap/extension-underline';
import Link from '@tiptap/extension-link';
import Placeholder from '@tiptap/extension-placeholder';
import Collaboration from '@tiptap/extension-collaboration';
import { CollaborationCursor } from './collaboration-cursor.extension';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { DocumentService } from '../../core/services/document.service';
import { CollabService, CollabSession, SaveState, userColorFor } from '../../core/services/collab.service';
import { SyncDocument } from '../../core/models/document.model';

/**
 * Collaborative document editor: a TipTap editor bound to a Yjs document
 * that syncs through the collaboration server.
 *
 * The editor opens only after the document metadata loads (which also
 * confirms workspace membership) and the collab session connects with a
 * fresh JWT. Remote cursors and selections come from Yjs awareness via
 * the CollaborationCursor extension; the participant list in the header
 * is the same awareness state. The save badge is driven by the session's
 * real sync/status events (see CollabService for the exact semantics).
 * The shared Yjs fragment is `content` — the name is client-side only;
 * the server relays binary updates and never interprets it.
 */
@Component({
  selector: 'app-document-editor',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './document-editor.component.html',
  styleUrl: './document-editor.component.scss',
})
export class DocumentEditorComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly documents = inject(DocumentService);
  private readonly collab = inject(CollabService);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder);
  private readonly cdr = inject(ChangeDetectorRef);

  protected readonly workspaceId = signal('');
  protected readonly documentId = signal('');
  protected readonly document = signal<SyncDocument | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly editorReady = signal(false);

  private readonly sessionSig = signal<CollabSession | null>(null);
  protected readonly saveState = computed<SaveState>(
    () => this.sessionSig()?.saveState() ?? 'connecting',
  );
  protected readonly collaborators = computed(() => this.sessionSig()?.collaborators() ?? []);
  protected readonly forbidden = computed(() => this.sessionSig()?.forbidden() ?? false);
  protected readonly connectionStatus = computed(() => this.sessionSig()?.status() ?? 'connecting');

  /**
   * Bumped on every editor transaction so the toolbar's `isActive`
   * states re-evaluate even for changes that arrive over the socket
   * (remote edits do not go through Angular's zone). Read by the
   * toolbar container via a data attribute — it carries no visual value.
   */
  protected readonly editorTick = signal(0);

  protected readonly linkForm = this.fb.nonNullable.group({
    url: ['', [Validators.maxLength(2000)]],
  });

  @ViewChild('editorHost') private editorHost?: ElementRef<HTMLElement>;

  private editor: Editor | null = null;
  private viewReady = false;
  private initStarted = false;

  constructor() {
    // A 4401 (no access) must not leave a live local editor behind:
    // once forbidden, the editor becomes read-only.
    effect(() => {
      if (this.forbidden()) {
        this.editor?.setEditable(false);
      }
    });
  }

  ngOnInit(): void {
    const workspaceId = this.route.snapshot.paramMap.get('id');
    const documentId = this.route.snapshot.paramMap.get('documentId');
    if (!workspaceId || !documentId) {
      this.error.set('No document selected.');
      this.loading.set(false);
      return;
    }
    this.workspaceId.set(workspaceId);
    this.documentId.set(documentId);
  }

  ngAfterViewInit(): void {
    this.viewReady = true;
    this.init();
    this.tryCreateEditor();
  }

  ngOnDestroy(): void {
    this.editor?.destroy();
    this.editor = null;
    this.sessionSig()?.destroy();
    this.sessionSig.set(null);
  }

  protected saveStateLabel(): string {
    switch (this.saveState()) {
      case 'saving':
        return 'Saving…';
      case 'saved':
        return 'Saved';
      case 'offline':
        return 'Offline — edits will sync when you reconnect';
      case 'reconnecting':
        return 'Reconnecting…';
      case 'connecting':
        return 'Connecting…';
    }
  }

  protected retry(): void {
    this.error.set(null);
    this.loading.set(true);
    this.editor?.destroy();
    this.editor = null;
    this.editorReady.set(false);
    this.sessionSig()?.destroy();
    this.sessionSig.set(null);
    this.initStarted = false;
    this.init();
  }

  protected isActive(name: string, attributes?: Record<string, unknown>): boolean {
    if (!this.editor) {
      return false;
    }
    return attributes ? this.editor.isActive(name, attributes) : this.editor.isActive(name);
  }

  protected toggleBold(): void {
    this.editor?.chain().focus().toggleBold().run();
  }

  protected toggleItalic(): void {
    this.editor?.chain().focus().toggleItalic().run();
  }

  protected toggleUnderline(): void {
    this.editor?.chain().focus().toggleUnderline().run();
  }

  protected setHeading(level: 1 | 2 | 3): void {
    this.editor?.chain().focus().toggleHeading({ level }).run();
  }

  protected setParagraph(): void {
    this.editor?.chain().focus().setParagraph().run();
  }

  protected toggleBulletList(): void {
    this.editor?.chain().focus().toggleBulletList().run();
  }

  protected toggleOrderedList(): void {
    this.editor?.chain().focus().toggleOrderedList().run();
  }

  protected toggleCodeBlock(): void {
    this.editor?.chain().focus().toggleCodeBlock().run();
  }

  protected undo(): void {
    this.editor?.chain().focus().undo().run();
  }

  protected redo(): void {
    this.editor?.chain().focus().redo().run();
  }

  protected applyLink(): void {
    const editor = this.editor;
    if (!editor) {
      return;
    }
    const url = this.linkForm.getRawValue().url.trim();
    if (url === '') {
      editor.chain().focus().unsetLink().run();
      return;
    }
    editor
      .chain()
      .focus()
      .setLink({ href: /^https?:\/\//i.test(url) ? url : `https://${url}` })
      .run();
  }

  protected removeLink(): void {
    this.editor?.chain().focus().unsetLink().run();
    this.linkForm.reset();
  }

  private init(): void {
    if (this.initStarted || this.workspaceId() === '' || this.documentId() === '') {
      return;
    }
    this.initStarted = true;
    void this.loadAndConnect();
  }

  private async loadAndConnect(): Promise<void> {
    try {
      const doc = await firstValueFrom(this.documents.getDocument(this.documentId()));
      this.document.set(doc);
      this.loading.set(false);
      // The editor host lives inside the card that only renders once
      // loading finishes, so render it synchronously here: the session
      // connects (and the editor is created) in the same microtask
      // chain, before change detection would otherwise run, and the
      // ViewChild would still be undefined.
      this.cdr.detectChanges();
      const session = await this.collab.connect(this.documentId());
      this.sessionSig.set(session);
      this.tryCreateEditor();
    } catch (err) {
      this.loading.set(false);
      if (err instanceof HttpErrorResponse) {
        this.error.set(
          err.status === 404
            ? 'This document does not exist or you do not have access to it.'
            : err.status === 403
              ? 'You do not have access to this document.'
              : 'Could not load the document. Try again.',
        );
      } else if (err instanceof Error) {
        this.error.set(err.message);
      } else {
        this.error.set('Could not open the document. Try again.');
      }
    }
  }

  private tryCreateEditor(): void {
    const session = this.sessionSig();
    if (this.editor !== null || session === null || !this.viewReady || !this.editorHost) {
      return;
    }
    const me = this.auth.currentUser();
    const user = {
      name: me?.displayName ?? 'Anonymous',
      color: userColorFor(me?.id ?? 'anonymous'),
      userId: me?.id ?? null,
    };
    this.editor = new Editor({
      element: this.editorHost.nativeElement,
      extensions: [
        // Collaboration ships its own Yjs undo/redo; StarterKit's
        // UndoRedo is incompatible with it and must stay off.
        StarterKit.configure({ undoRedo: false }),
        Underline,
        Link.configure({ openOnClick: false }),
        Placeholder.configure({ placeholder: 'Start writing…' }),
        Collaboration.configure({ document: session.doc, field: 'content' }),
        CollaborationCursor.configure({ provider: session.provider, user }),
      ],
      editorProps: {
        attributes: { class: 'doc-editor-content' },
      },
      onTransaction: () => {
        this.editorTick.update((tick) => tick + 1);
      },
    });
    this.editorReady.set(true);
  }
}
