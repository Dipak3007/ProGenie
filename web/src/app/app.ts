import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { AccountGates } from './core/layout/account-gates';
import { Footer } from './core/layout/footer';
import { Header } from './core/layout/header';
import { Feedback } from './core/ui/feedback';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Header, AccountGates, Footer, Feedback],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a href="#main" class="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-full focus:bg-gold focus:px-4 focus:py-2 focus:text-ink">
      Skip to content
    </a>
    <div class="flex min-h-dvh flex-col">
      <pg-header />
      <pg-account-gates />
      <main id="main" class="flex-1">
        <router-outlet />
      </main>
      <pg-footer />
    </div>
    <pg-feedback />
  `,
})
export class App {}
