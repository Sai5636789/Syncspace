import { Component, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { AuthService } from '../../core/services/auth.service';

/**
 * Signed-in home. Greets the user and lists their workspaces; the
 * workspace list is wired to the workspaces API in the workspaces
 * milestone — until then it states plainly that there are none yet.
 */
@Component({
  selector: 'app-dashboard',
  imports: [MatCardModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent {
  protected readonly auth = inject(AuthService);
}
