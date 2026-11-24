import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { BehaviorSubject, Observable, catchError, filter, switchMap, take, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

let refreshing = false;
const refreshSubject = new BehaviorSubject<string | null>(null);

/**
 * Attaches the JWT access token to API requests. On a 401, refreshes the
 * token once (concurrent requests wait for the single refresh) and retries
 * the failed request. If refresh fails, the session is cleared and the user
 * is sent to /login.
 */
export const authInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next: HttpHandlerFn): Observable<HttpEvent<unknown>> => {
  const auth = inject(AuthService);
  const isAuthCall = req.url.includes('/auth/');

  const withToken = (request: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> =>
    token && !isAuthCall
      ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : request;

  return next(withToken(req, auth.token)).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401 || isAuthCall) {
        return throwError(() => error);
      }
      if (auth.token === null) {
        auth.logout();
        return throwError(() => error);
      }
      if (!refreshing) {
        refreshing = true;
        refreshSubject.next(null);
        return auth.refresh().pipe(
          switchMap((res) => {
            refreshing = false;
            refreshSubject.next(res.accessToken);
            return next(withToken(req, res.accessToken));
          }),
          catchError((refreshError: unknown) => {
            refreshing = false;
            auth.logout();
            return throwError(() => refreshError);
          }),
        );
      }
      return refreshSubject.pipe(
        filter((token): token is string => token !== null),
        take(1),
        switchMap((token) => next(withToken(req, token))),
      );
    }),
  );
};
