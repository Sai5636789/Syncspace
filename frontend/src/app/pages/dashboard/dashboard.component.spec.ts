import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { Workspace, WorkspaceMember, WorkspaceService } from '../../core/services/workspace.service';
import { PresenceService } from '../../core/services/presence.service';
import { PresenceMember } from '../../core/models/presence.model';
import { DashboardComponent } from './dashboard.component';

describe('DashboardComponent', () => {
  const workspaces: Workspace[] = [
    {
      id: 'ws-1',
      name: 'Alpha Team',
      description: null,
      ownerId: 'user-1',
      role: 'OWNER',
      createdAt: '2026-11-24T00:00:00Z',
      updatedAt: '2026-11-24T00:00:00Z',
    },
  ];

  async function setup(
    list: Workspace[],
    roster: WorkspaceMember[] = [],
    presence: PresenceMember[] = [],
  ) {
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideHttpClient(),
        provideRouter([]),
        {
          provide: WorkspaceService,
          useValue: {
            listMine: () => of(list),
            create: () => of(list[0]),
            listMembers: () => of(roster),
            leave: () => of(undefined),
          } satisfies Partial<WorkspaceService>,
        },
        {
          provide: PresenceService,
          useValue: { listPresence: () => of(presence) },
        },
      ],
    }).compileComponents();
    const auth = TestBed.inject(AuthService);
    auth.currentUser.set({ id: 'user-1', email: 'sai@example.com', displayName: 'Sai' });
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  it('lists the user workspaces from the API', async () => {
    const fixture = await setup(workspaces);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Alpha Team');
    expect(text).toContain('OWNER');
  });

  it('shows the empty state when there are no workspaces', async () => {
    const fixture = await setup([]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('not a member of any workspace yet');
  });

  it('shows presence on the selected workspace member list', async () => {
    const roster: WorkspaceMember[] = [
      {
        userId: 'user-1',
        email: 'sai@example.com',
        displayName: 'Sai',
        role: 'OWNER',
        joinedAt: '2026-11-24T00:00:00Z',
      },
      {
        userId: 'user-2',
        email: 'ana@example.com',
        displayName: 'Ana',
        role: 'MEMBER',
        joinedAt: '2026-11-24T00:00:00Z',
      },
    ];
    const presence: PresenceMember[] = [
      {
        userId: 'user-1',
        email: 'sai@example.com',
        displayName: 'Sai',
        role: 'OWNER',
        joinedAt: '2026-11-24T00:00:00Z',
        status: 'online',
        lastSeenAt: '2026-11-26T12:00:00Z',
      },
      {
        userId: 'user-2',
        email: 'ana@example.com',
        displayName: 'Ana',
        role: 'MEMBER',
        joinedAt: '2026-11-24T00:00:00Z',
        status: 'offline',
        lastSeenAt: '2026-11-26T11:00:00Z',
      },
    ];
    const fixture = await setup(workspaces, roster, presence);
    const component = fixture.componentInstance as unknown as {
      select: (workspace: Workspace) => void;
    };
    component.select(workspaces[0]);
    fixture.detectChanges();
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Sai');
    expect(text).toContain('Ana');
    expect(text).toContain('Online');
    expect(text).toContain('Last seen');
  });
});
