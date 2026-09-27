import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Logo } from '../../shared/ui/logo';

@Component({
  selector: 'pg-footer',
  imports: [RouterLink, Logo],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <footer class="bg-navy text-brand-mist">
      <div class="container-page grid gap-10 py-14 sm:grid-cols-2 lg:grid-cols-5">
        <div class="sm:col-span-2 lg:col-span-1">
          <pg-logo tone="dark" [size]="40" />
          <p class="mt-4 max-w-xs text-sm text-brand-soft">Professional Genies for every home job. Verified, fairly priced, on time.</p>
        </div>
        <div>
          <h2 class="font-sans text-sm font-semibold uppercase tracking-widest text-white">Customers</h2>
          <ul class="mt-4 space-y-2 text-sm">
            <li><a routerLink="/services" class="hover:text-white">All services</a></li>
            <li><a routerLink="/login" class="hover:text-white">My bookings</a></li>
            <li><a routerLink="/" fragment="contact" class="hover:text-white">Contact us</a></li>
          </ul>
        </div>
        <div>
          <h2 class="font-sans text-sm font-semibold uppercase tracking-widest text-white">Genies</h2>
          <ul class="mt-4 space-y-2 text-sm">
            <li><a routerLink="/register" [queryParams]="{ role: 'GENIE' }" class="hover:text-white">Become a Genie</a></li>
            <li><a routerLink="/genie" class="hover:text-white">Genie console</a></li>
          </ul>
        </div>
        <div>
          <h2 class="font-sans text-sm font-semibold uppercase tracking-widest text-white">Policies</h2>
          <ul class="mt-4 space-y-2 text-sm">
            <li><a routerLink="/legal/terms" class="hover:text-white">Terms of Service</a></li>
            <li><a routerLink="/legal/privacy" class="hover:text-white">Privacy Policy</a></li>
            <li><a routerLink="/legal/refunds" class="hover:text-white">Cancellation &amp; refunds</a></li>
            <li><a routerLink="/legal/genie-agreement" class="hover:text-white">Genie Partner Agreement</a></li>
          </ul>
        </div>
        <div>
          <h2 class="font-sans text-sm font-semibold uppercase tracking-widest text-white">Now serving</h2>
          <p class="mt-4 text-sm">Ahmedabad, Gujarat</p>
        </div>
      </div>
      <div class="border-t border-white/10">
        <div class="container-page flex flex-col gap-2 py-5 text-xs text-brand-soft sm:flex-row sm:items-center sm:justify-between">
          <p>© {{ year }} ProGenie. Professional Genie.</p>
          <p>
            Complaints about how we handle your data?
            <a routerLink="/legal/privacy" fragment="grievance-h" class="font-semibold text-brand-mist hover:text-white">Contact our grievance officer</a>
          </p>
        </div>
      </div>
    </footer>
  `,
})
export class Footer {
  protected readonly year = new Date().getFullYear();
}
