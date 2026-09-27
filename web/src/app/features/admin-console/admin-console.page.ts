import { ChangeDetectionStrategy, Component, Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterOutlet } from '@angular/router';
import {
  LucideBanknote as Banknote,
  LucideCalendarDays as CalendarDays,
  LucideFileText as FileText,
  LucideInbox as InboxIcon,
  LucideLayoutDashboard as LayoutDashboard,
  LucideLifeBuoy as LifeBuoy,
  LucideSend as Send,
  LucideSettings as Settings,
  LucideShieldCheck as ShieldCheck,
  LucideStore as Store,
  LucideUser as User,
  LucideUserX as UserX,
  LucideUsers as Users,
} from '@lucide/angular';

import { API } from '../../core/api/api';
import { ContactMessage, OutboundMessage, Page, QueueItem, TicketSummary } from '../../core/api/models';
import { ConsoleShell, NavItem } from '../../shared/ui/kit';

/** Badge counts for the admin sidebar (pending verifications, new messages, open complaints, failed messages). */
@Injectable({ providedIn: 'root' })
export class AdminCounts {
  private readonly http = inject(HttpClient);
  readonly pending = signal(0);
  readonly messages = signal(0);
  readonly tickets = signal(0);
  readonly failed = signal(0);

  refresh(): void {
    this.http.get<Page<QueueItem>>(`${API}/admin/genies`, { params: { status: 'UNDER_REVIEW', size: 1 } }).subscribe({
      next: (p) => this.pending.set(p.total),
      error: () => undefined,
    });
    this.http.get<Page<ContactMessage>>(`${API}/admin/contact-messages`, { params: { status: 'NEW', size: 1 } }).subscribe({
      next: (p) => this.messages.set(p.total),
      error: () => undefined,
    });
    this.http.get<Page<TicketSummary>>(`${API}/admin/tickets`, { params: { status: 'ACTIVE', size: 1 } }).subscribe({
      next: (p) => this.tickets.set(p.total),
      error: () => undefined,
    });
    this.http.get<Page<OutboundMessage>>(`${API}/admin/messages`, { params: { status: 'FAILED', size: 1 } }).subscribe({
      next: (p) => this.failed.set(p.total),
      error: () => undefined,
    });
  }
}

@Component({
  selector: 'pg-admin-console-page',
  imports: [RouterOutlet, ConsoleShell],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-console-shell eyebrow="ProGenie admin" title="Back office" [items]="nav()">
      <router-outlet />
    </pg-console-shell>
  `,
})
export default class AdminConsolePage {
  private readonly counts = inject(AdminCounts);

  protected readonly nav = computed<NavItem[]>(() => [
    { path: '/admin', label: 'Overview', icon: LayoutDashboard, exact: true },
    { path: '/admin/genies', label: 'Genies', icon: ShieldCheck, badge: this.counts.pending() },
    { path: '/admin/bookings', label: 'Bookings', icon: CalendarDays },
    { path: '/admin/tickets', label: 'Complaints', icon: LifeBuoy, badge: this.counts.tickets() },
    { path: '/admin/users', label: 'Users', icon: Users },
    { path: '/admin/catalog', label: 'Catalog & pricing', icon: Store },
    { path: '/admin/finance', label: 'Finance', icon: Banknote },
    { path: '/admin/inbox', label: 'Inbox', icon: InboxIcon, badge: this.counts.messages() },
    { path: '/admin/messages', label: 'Messages', icon: Send, badge: this.counts.failed() },
    { path: '/admin/legal', label: 'Policies', icon: FileText },
    { path: '/admin/deletions', label: 'Deletions', icon: UserX },
    { path: '/admin/account', label: 'Account', icon: User },
    { path: '/admin/settings', label: 'Settings', icon: Settings },
  ]);

  constructor() {
    this.counts.refresh();
  }
}
