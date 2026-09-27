import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import {
  LucideBriefcase as Briefcase,
  LucideCalendarDays as CalendarDays,
  LucideClipboardList as ClipboardList,
  LucideLayoutDashboard as LayoutDashboard,
  LucideStar as Star,
  LucideLifeBuoy as LifeBuoy,
  LucideSettings as Settings,
  LucideUser as User,
  LucideWallet as Wallet,
} from '@lucide/angular';

import { ConsoleShell, NavItem } from '../../shared/ui/kit';
import { GenieStore } from './genie-store';

/** Genie console layout. Loads the profile once; child pages read it from GenieStore. */
@Component({
  selector: 'pg-genie-console-page',
  imports: [RouterOutlet, ConsoleShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-console-shell eyebrow="Genie console" [title]="store.profile()?.fullName ?? 'Genie console'" [items]="nav()">
      <router-outlet />
    </pg-console-shell>
  `,
})
export default class GenieConsolePage {
  protected readonly store = inject(GenieStore);

  protected readonly nav = computed<NavItem[]>(() => [
    { path: '/genie', label: 'Dashboard', icon: LayoutDashboard, exact: true },
    { path: '/genie/jobs', label: 'Jobs', icon: Briefcase, badge: this.store.requests(), also: ['/genie/bookings'] },
    { path: '/genie/onboarding', label: this.store.approved() ? 'Profile & services' : 'Get verified', icon: ClipboardList },
    { path: '/genie/availability', label: 'Availability', icon: CalendarDays },
    { path: '/genie/wallet', label: 'Earnings', icon: Wallet },
    { path: '/genie/reviews', label: 'Reviews', icon: Star },
    { path: '/genie/tickets', label: 'Reported problems', icon: LifeBuoy },
    { path: '/genie/account', label: 'Account', icon: User },
    { path: '/genie/privacy', label: 'Settings & privacy', icon: Settings },
  ]);

  constructor() {
    this.store.load();
  }
}
