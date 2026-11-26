import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AuthService } from '../../core/services/auth.service';
import { Workspace, WorkspaceMember, WorkspaceService } from '../../core/services/workspace.service';
import { PresenceMember } from '../../core/models/presence.model';
import { PresenceService, formatLastSeen } from '../../core/services/presence.service';

/** How often the dashboard re-reads presence for the selected workspace (REST snapshot, no STOMP here). */
export const DASHBOARD_PRESENCE_REFRESH_MS = 30000;

/**
 * Signed-in home. Lists the user's real workspaces from the API, creates
 * new ones, shows a workspace's members, and lets non-owners leave.
 * The member list shows live presence from GET /presence, refreshed
 * every 30 s while a workspace is selected. This page opens no STOMP
 * connection, so it does not heartbeat itself — its own user appears
 * online only while a workspace view (chat) is open elsewhere.
 */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, ReactiveFormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnDestroy {
  protected readonly auth = inject(AuthService);
  private readonly workspaces = inject(WorkspaceService);
  private readonly presenceService = inject(PresenceService);
  private readonly fb = inject(FormBuilder);

  protected readonly workspaceList = signal<Workspace[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly selected = signal<Workspace | null>(null);
  protected readonly members = signal<WorkspaceMember[]>([]);
  protected readonly presence = signal<PresenceMember[]>([]);
  /**
   * Member roster with presence overlaid from the latest GET /presence
   * snapshot; members missing from the snapshot read offline.
   */
  protected readonly displayMembers = computed(() => {
    const live = new Map(this.presence().map((member) => [member.userId, member]));
    return this.members().map((member) => {
      const state = live.get(member.userId);
      return {
        ...member,
        status: state?.status ?? ('offline' as const),
        lastSeenAt: state?.lastSeenAt ?? null,
      };
    });
  });
  protected readonly creating = signal(false);

  private presenceRefreshTimer: ReturnType<typeof setInterval> | null = null;

  protected readonly createForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    description: [''],
  });

  constructor() {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.clearPresenceRefresh();
  }

  /** "Online" or "Last seen …" for a member — always real presence state, never hardcoded. */
  protected presenceLabel(member: { status: 'online' | 'offline'; lastSeenAt: string | null }): string {
    return member.status === 'online' ? 'Online' : formatLastSeen(member.lastSeenAt);
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
    this.loadPresence(workspace.id);
    this.clearPresenceRefresh();
    this.presenceRefreshTimer = setInterval(
      () => this.loadPresence(workspace.id),
      DASHBOARD_PRESENCE_REFRESH_MS,
    );
  }

  protected leave(workspace: Workspace): void {
    this.workspaces.leave(workspace.id).subscribe({
      next: () => {
        this.workspaceList.update((list) => list.filter((item) => item.id !== workspace.id));
        if (this.selected()?.id === workspace.id) {
          this.selected.set(null);
          this.members.set([]);
          this.presence.set([]);
          this.clearPresenceRefresh();
        }
      },
      error: () => this.error.set('Could not leave the workspace. Try again.'),
    });
  }

  private loadPresence(workspaceId: string): void {
    this.presenceService.listPresence(workspaceId).subscribe({
      next: (presence) => this.presence.set(presence),
      error: () => this.presence.set([]),
    });
  }

  private clearPresenceRefresh(): void {
    if (this.presenceRefreshTimer !== null) {
      clearInterval(this.presenceRefreshTimer);
      this.presenceRefreshTimer = null;
    }
  }
}
