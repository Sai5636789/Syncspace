import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { DocumentService } from '../../core/services/document.service';
import { WorkspaceService } from '../../core/services/workspace.service';
import { SyncDocument } from '../../core/models/document.model';

/**
 * Document management for one workspace: lists the workspace's documents
 * (metadata from REST — content lives in Yjs), creates, renames, and
 * deletes them, and links each one into the collaborative editor. A
 * rename by another user does not live-update this list — the list is
 * re-read from REST every time the page opens.
 */
@Component({
  selector: 'app-documents',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './documents.component.html',
  styleUrl: './documents.component.scss',
})
export class DocumentsComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly documents = inject(DocumentService);
  private readonly workspaces = inject(WorkspaceService);
  private readonly fb = inject(FormBuilder);

  protected readonly workspaceId = signal('');
  protected readonly workspaceName = signal('');
  protected readonly docs = signal<SyncDocument[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected readonly showCreate = signal(false);
  protected readonly renamingId = signal<string | null>(null);
  protected readonly confirmDeleteId = signal<string | null>(null);

  protected readonly createForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
  });
  protected readonly renameForm = this.fb.nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
  });

  ngOnInit(): void {
    const workspaceId = this.route.snapshot.paramMap.get('id');
    if (!workspaceId) {
      this.error.set('No workspace selected.');
      this.loading.set(false);
      return;
    }
    this.workspaceId.set(workspaceId);
    this.workspaces.get(workspaceId).subscribe({
      next: (workspace) => this.workspaceName.set(workspace.name),
      error: () => this.workspaceName.set('Documents'),
    });
    this.loadDocuments();
  }

  protected createDocument(): void {
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    const title = this.createForm.getRawValue().title.trim();
    if (title === '') {
      return;
    }
    this.creating.set(true);
    this.documents.createDocument(this.workspaceId(), title).subscribe({
      next: (doc) => {
        this.creating.set(false);
        this.createForm.reset();
        this.showCreate.set(false);
        this.docs.update((list) => [doc, ...list]);
      },
      error: (err: { status?: number }) => {
        this.creating.set(false);
        this.error.set(
          err.status === 403
            ? 'You need to be a member (not just a viewer) to create documents in this workspace.'
            : 'Could not create the document. Try again.',
        );
      },
    });
  }

  protected startRename(doc: SyncDocument): void {
    this.renamingId.set(doc.id);
    this.confirmDeleteId.set(null);
    this.renameForm.setValue({ title: doc.title });
  }

  protected cancelRename(): void {
    this.renamingId.set(null);
  }

  protected saveRename(doc: SyncDocument): void {
    if (this.renameForm.invalid) {
      this.renameForm.markAllAsTouched();
      return;
    }
    const title = this.renameForm.getRawValue().title.trim();
    if (title === '' || title === doc.title) {
      this.renamingId.set(null);
      return;
    }
    this.documents.renameDocument(doc.id, title).subscribe({
      next: (updated) => {
        this.renamingId.set(null);
        this.docs.update((list) => list.map((item) => (item.id === updated.id ? updated : item)));
      },
      error: (err: { status?: number }) => {
        this.error.set(
          err.status === 403
            ? 'You need to be a member (not just a viewer) to rename documents in this workspace.'
            : 'Could not rename the document. Try again.',
        );
      },
    });
  }

  protected deleteDocument(doc: SyncDocument): void {
    if (this.confirmDeleteId() !== doc.id) {
      // First click arms the confirm; second click deletes.
      this.confirmDeleteId.set(doc.id);
      return;
    }
    this.confirmDeleteId.set(null);
    this.documents.deleteDocument(doc.id).subscribe({
      next: () => {
        this.docs.update((list) => list.filter((item) => item.id !== doc.id));
        if (this.renamingId() === doc.id) {
          this.renamingId.set(null);
        }
      },
      error: (err: { status?: number }) => {
        this.error.set(
          err.status === 403
            ? 'You need to be a member (not just a viewer) to delete documents in this workspace.'
            : 'Could not delete the document. Try again.',
        );
      },
    });
  }

  private loadDocuments(): void {
    this.documents.listDocuments(this.workspaceId()).subscribe({
      next: (list) => {
        this.docs.set(list.documents);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not load the documents. Is the backend running?');
      },
    });
  }
}
