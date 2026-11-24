import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, map, of } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface BackendHealth {
  status: string;
  service: string;
}

/**
 * Backend connectivity probe used by the landing page and the app shell
 * status indicator. A failed probe means the backend is unreachable —
 * never faked as healthy.
 */
@Injectable({ providedIn: 'root' })
export class HealthService {
  private readonly apiUrl = environment.apiUrl;

  constructor(private readonly http: HttpClient) {}

  checkBackend(): Observable<BackendHealth | null> {
    return this.http.get<BackendHealth>(`${this.apiUrl}/health`).pipe(
      map((health) => health),
      catchError(() => of(null)),
    );
  }
}
