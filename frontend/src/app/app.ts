import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatToolbarModule } from '@angular/material/toolbar';
import { AuthService } from './core/services/auth.service';
import { HealthService } from './core/services/health.service';

/**
 * App shell: top toolbar with the SyncSpace brand, a live backend-status
 * indicator, and the signed-in user's menu. The backend status is probed
 * for real on load — it is never hardcoded as healthy.
 */
@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet, MatButtonModule, MatIconModule, MatMenuModule, MatToolbarModule],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly backendOk = signal<boolean | null>(null);
  private readonly router = inject(Router);

  constructor(health: HealthService) {
    health.checkBackend().subscribe((result) => this.backendOk.set(result !== null));
    const restored = this.auth.restoreSession();
    if (restored) {
      restored.subscribe({ error: () => undefined });
    }
  }

  protected logout(): void {
    this.auth.logout();
    void this.router.navigate(['/']);
  }
}
