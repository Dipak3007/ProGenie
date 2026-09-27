import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { Role } from '../api/models';
import { AuthStore } from './auth-store';

/** Requires a logged-in user (optionally with one of the given roles); otherwise sends them to /login. */
export function requireRole(...roles: Role[]): CanActivateFn {
  return (_route, state) => {
    const auth = inject(AuthStore);
    const router = inject(Router);
    if (!auth.isLoggedIn()) {
      return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
    }
    if (roles.length > 0 && !roles.includes(auth.role()!)) {
      return router.createUrlTree([auth.homeFor(auth.role())]);
    }
    return true;
  };
}

/** Login/register pages are only for guests. */
export const guestOnly: CanActivateFn = () => {
  const auth = inject(AuthStore);
  return auth.isLoggedIn() ? inject(Router).createUrlTree([auth.homeFor(auth.role())]) : true;
};
