import { Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthResponse, User } from '../models/auth.model';

/**
 * Authentication state and API calls.
 *
 * The access token lives in memory (a signal) so it never persists to
 * localStorage. The refresh token is stored in sessionStorage and is only
 * ever sent to /auth/refresh and /auth/logout. A silent refresh runs on
 * 401 via the auth interceptor.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly apiUrl = `${environment.apiUrl}/auth`;
  private readonly refreshTokenKey = 'syncspace.refresh_token';

  readonly currentUser = signal<User | null>(null);
  private accessToken: string | null = null;

  constructor(
    private readonly http: HttpClient,
    private readonly router: Router,
  ) {}

  get token(): string | null {
    return this.accessToken;
  }

  get isAuthenticated(): boolean {
    return this.currentUser() !== null && this.accessToken !== null;
  }

  register(email: string, displayName: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/register`, { email, displayName, password })
      .pipe(tap((res) => this.applyAuthResponse(res)));
  }

  login(email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/login`, { email, password })
      .pipe(tap((res) => this.applyAuthResponse(res)));
  }

  refresh(): Observable<AuthResponse> {
    const refreshToken = sessionStorage.getItem(this.refreshTokenKey);
    if (!refreshToken) {
      throw new Error('No refresh token available');
    }
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/refresh`, { refreshToken })
      .pipe(tap((res) => this.applyAuthResponse(res)));
  }

  logout(): void {
    const refreshToken = sessionStorage.getItem(this.refreshTokenKey);
    if (refreshToken) {
      // Best-effort server-side revocation; the local session ends regardless.
      this.http.post(`${this.apiUrl}/logout`, { refreshToken }).subscribe({ error: () => undefined });
    }
    this.clearSession();
    void this.router.navigate(['/login']);
  }

  /** Restores the session on app start if a refresh token exists. */
  restoreSession(): Observable<AuthResponse> | null {
    if (!sessionStorage.getItem(this.refreshTokenKey)) {
      return null;
    }
    return this.refresh();
  }

  private applyAuthResponse(res: AuthResponse): void {
    this.accessToken = res.accessToken;
    this.currentUser.set(res.user);
    sessionStorage.setItem(this.refreshTokenKey, res.refreshToken);
  }

  private clearSession(): void {
    this.accessToken = null;
    this.currentUser.set(null);
    sessionStorage.removeItem(this.refreshTokenKey);
  }
}
