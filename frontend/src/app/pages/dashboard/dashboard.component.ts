import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AuthService } from '../../core/services/auth.service';
import { Workspace, WorkspaceMember, WorkspaceService } from '../../core/services/workspace.service';

/**
 * Signed-in home. Lists the user's real workspaces from the API, creates
 * new ones, shows a workspace's members, and lets non-owners leave.
 */
@Component({
  selector: 'app-dashboard',
  imports: [ReactiveFormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent {
  protected readonly auth = inject(AuthService);
  private readonly workspaces = inject(WorkspaceService);
  private readonly fb = inject(FormBuilder);

  protected readonly workspaceList = signal<Workspace[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly selected = signal<Workspace | null>(null);
  protected readonly members = signal<WorkspaceMember[]>([]);
  protected readonly creating = signal(false);

  protected readonly createForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    description: [''],
  });

  constructor() {
    this.refresh();
  }

  protected refresh(): void {
    this.loading.set(true);
    this.error.set(null);
    this.workspaces.listMine().subscribe({
      next: (list) => {
        this.workspaceList.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load your workspaces. Is the backend running?');
        this.loading.set(false);
      },
    });
  }

  protected createWorkspace(): void {
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    const { name, description } = this.createForm.getRawValue();
    this.creating.set(true);
    this.workspaces.create(name, description || null).subscribe({
      next: (workspace) => {
        this.creating.set(false);
        this.createForm.reset();
        this.workspaceList.update((list) => [workspace, ...list]);
      },
      error: () => {
        this.creating.set(false);
        this.error.set('Could not create the workspace. Try again.');
      },
    });
  }

  protected select(workspace: Workspace): void {
    this.selected.set(workspace);
    this.workspaces.listMembers(workspace.id).subscribe({
      next: (members) => this.members.set(members),
      error: () => this.members.set([]),
    });
  }

  protected leave(workspace: Workspace): void {
    this.workspaces.leave(workspace.id).subscribe({
      next: () => {
        this.workspaceList.update((list) => list.filter((item) => item.id !== workspace.id));
        if (this.selected()?.id === workspace.id) {
          this.selected.set(null);
          this.members.set([]);
        }
      },
      error: () => this.error.set('Could not leave the workspace. Try again.'),
    });
  }
}
