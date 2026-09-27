import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';

import { ProblemDetail } from '../api/models';
import { AuthStore } from './auth-store';
import { PhoneVerification } from './phone-verification';

/** Never send a (possibly expired) token here: Spring would reject the call before it reaches the endpoint. */
const TOKENLESS = /\/api\/v1\/auth\/(login|register|refresh|logout|otp\/verify|password\/reset)$/;

function withBearer(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}

/**
 * 1. Adds "Authorization: Bearer <token>" to API calls.
 * 2. On a 401, refreshes the access token once and retries the original request.
 * 3. On 428 CONSENT_REQUIRED / 403 PHONE_NOT_VERIFIED, opens the matching app-wide dialog.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthStore);
  const phone = inject(PhoneVerification);

  if (!req.url.startsWith('/api/')) {
    return next(req);
  }
  // Login, refresh and friends never carry a token (and never trigger a refresh-and-retry). Other /auth calls
  // such as change-password and the verify-phone code do, like any API call.
  if (TOKENLESS.test(req.url)) {
    return next(req);
  }

  return next(withBearer(req, auth.token())).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse) {
        const body = err.error as ProblemDetail | null;
        // Gates the whole app reacts to, wherever the call came from.
        if (err.status === 428 && body?.code === 'CONSENT_REQUIRED') {
          auth.markPendingConsents(body.pendingConsents ?? []);
        } else if (err.status === 403 && body?.code === 'PHONE_NOT_VERIFIED') {
          phone.open();
        }
      }
      if (!(err instanceof HttpErrorResponse) || err.status !== 401 || !auth.isLoggedIn()) {
        return throwError(() => err);
      }
      return auth.refresh().pipe(
        switchMap((token) => next(withBearer(req, token))),
        catchError((refreshErr: unknown) => {
          auth.clear();
          return throwError(() => refreshErr);
        }),
      );
    }),
  );
};
