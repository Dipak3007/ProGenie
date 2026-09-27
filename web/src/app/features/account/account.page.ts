import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import {
  LucideCalendarDays as CalendarDays,
  LucideHeart as Heart,
  LucideMapPin as MapPin,
  LucideLifeBuoy as LifeBuoy,
  LucideSettings as Settings,
  LucideUser as User,
} from '@lucide/angular';

import { AuthStore } from '../../core/auth/auth-store';
import { ConsoleShell, NavItem } from '../../shared/ui/kit';

/** Customer area layout: bookings, saved addresses, favourites and profile. */
@Component({
  selector: 'pg-account-page',
  imports: [RouterOutlet, ConsoleShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-console-shell [eyebrow]="'Hi ' + auth.firstName()" title="My account" [items]="nav">
      <router-outlet />
    </pg-console-shell>
  `,
})
export default class AccountPage {
  protected readonly auth = inject(AuthStore);
  protected readonly nav: NavItem[] = [
    { path: '/account/bookings', label: 'Bookings', icon: CalendarDays },
    { path: '/account/addresses', label: 'Addresses', icon: MapPin },
    { path: '/account/favourites', label: 'Favourites', icon: Heart },
    { path: '/account/tickets', label: 'Reported problems', icon: LifeBuoy },
    { path: '/account/profile', label: 'Profile', icon: User },
    { path: '/account/privacy', label: 'Settings & privacy', icon: Settings },
  ];
}
