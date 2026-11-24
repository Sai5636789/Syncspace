import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { Workspace, WorkspaceService } from '../../core/services/workspace.service';
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

  async function setup(list: Workspace[]) {
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
            listMembers: () => of([]),
            leave: () => of(undefined),
          } satisfies Partial<WorkspaceService>,
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
});
