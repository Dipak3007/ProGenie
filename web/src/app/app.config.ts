import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { DATE_PIPE_DEFAULT_OPTIONS } from '@angular/common';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling, withViewTransitions } from '@angular/router';

import { routes } from './app.routes';
import { AuthStore } from './core/auth/auth-store';
import { authInterceptor } from './core/auth/auth.interceptor';

/**
 * App-wide providers. Angular 21 apps are zoneless by default: change detection is driven by signals.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withViewTransitions(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top', anchorScrolling: 'enabled' }),
    ),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
    // Show every date and time in India time (trial city: Ahmedabad), whatever the device's time zone.
    { provide: DATE_PIPE_DEFAULT_OPTIONS, useValue: { timezone: '+0530' } },
    // Restore the session (refresh cookie → access token) before the first route is resolved.
    provideAppInitializer(() => inject(AuthStore).restoreSession()),
  ],
};
