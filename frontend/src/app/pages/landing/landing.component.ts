import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../core/services/auth.service';
import { HealthService } from '../../core/services/health.service';

/**
 * Public landing page. Shows what SyncSpace does and the live backend
 * status — the status is probed for real, never hardcoded.
 */
@Component({
  selector: 'app-landing',
  imports: [RouterLink, MatButtonModule, MatCardModule, MatIconModule],
  templateUrl: './landing.component.html',
  styleUrl: './landing.component.scss',
})
export class LandingComponent {
  protected readonly auth = inject(AuthService);
  protected readonly backendOk = signal<boolean | null>(null);

  constructor(health: HealthService) {
    health.checkBackend().subscribe((result) => this.backendOk.set(result !== null));
  }
}
