import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { LucideDynamicIcon, LucideSearch as SearchIcon, LucideUsers as Users } from '@lucide/angular';

import { API, errorMessage, query } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { Page, UserRow } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth-store';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { Avatar } from '../../shared/ui/avatar';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';

@Component({
  selector: 'pg-admin-users-page',
  imports: [RouterLink, DatePipe, LucideDynamicIcon, Avatar, EmptyState, PageHead, Pager, Skeleton, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Users" subtitle="Customers, Genies and admins. Suspending someone signs them out everywhere." />

    <div class="mt-5 flex flex-col gap-3 lg:flex-row lg:items-center">
      <pg-tabs class="min-w-0 flex-1" [items]="roles" [value]="role()" label="Role" (valueChange)="role.set($event); page$.set(0)" />
      <select class="field w-full py-2.5 lg:w-44" [value]="status()" (change)="status.set($any($event.target).value); page$.set(0)" aria-label="Status">
        <option value="">Any status</option>
        <option value="ACTIVE">Active</option>
        <option value="SUSPENDED">Suspended</option>
      </select>
      <form class="relative lg:w-64" role="search" (submit)="$event.preventDefault(); q.set(term); page$.set(0)">
        <label for="u-q" class="sr-only">Search users</label>
        <svg [lucideIcon]="SearchIcon" [size]="16" class="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" aria-hidden="true"></svg>
        <input id="u-q" type="search" class="field rounded-full py-2.5 pl-10" placeholder="Name, phone or email" [value]="term" (input)="term = $any($event.target).value" (search)="q.set(term)" />
      </form>
    </div>

    <div class="mt-5">
      @if (list.value(); as page) {
        <ul class="space-y-2">
          @for (u of page.items; track u.id) {
            <li class="card flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
              <div class="flex min-w-0 flex-1 items-center gap-3">
                <pg-avatar [name]="u.fullName" [size]="40" />
                <div class="min-w-0">
                  <p class="flex flex-wrap items-center gap-2 font-semibold">
                    {{ u.fullName }} <span class="chip">{{ roleLabel(u.role) }}</span> <pg-status [status]="u.status" />
                  </p>
                  <p class="truncate text-sm text-muted">{{ u.phone }}{{ u.email ? ' · ' + u.email : '' }} · joined {{ u.createdAt | date: 'd MMM y' }} · {{ u.bookingCount }} bookings</p>
                </div>
              </div>
              <div class="flex gap-2 sm:shrink-0">
                @if (u.role === 'GENIE') {
                  <a [routerLink]="['/admin/genies', u.id]" class="btn-ghost min-h-10 px-4">Genie profile</a>
                }
                @if (u.id !== me()) {
                  @if (u.status === 'ACTIVE') {
                    <button type="button" class="btn min-h-10 border border-rose-200 bg-white px-4 text-rose-700 hover:bg-rose-50" (click)="setStatus(u, 'SUSPENDED')">Suspend</button>
                  } @else if (u.status === 'SUSPENDED') {
                    <button type="button" class="btn-ghost min-h-10 px-4" (click)="setStatus(u, 'ACTIVE')">Reactivate</button>
                  }
                }
              </div>
            </li>
          } @empty {
            <pg-empty [icon]="Users" title="No users match" />
          }
        </ul>
        <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="page$.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="6" />
      }
    </div>
  `,
})
export default class AdminUsersPage {
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly auth = inject(AuthStore);

  protected readonly role = signal('ALL');
  protected readonly status = signal('');
  protected readonly q = signal('');
  protected term = '';
  protected readonly page$ = signal(0);
  protected readonly roles: TabItem[] = [
    { value: 'ALL', label: 'Everyone' },
    { value: 'CUSTOMER', label: 'Customers' },
    { value: 'GENIE', label: 'Genies' },
    { value: 'ADMIN', label: 'Admins' },
  ];

  protected readonly list = httpResource<Page<UserRow>>(
    () =>
      `${API}/admin/users${query({
        role: this.role() === 'ALL' ? null : this.role(),
        status: this.status() || null,
        q: this.q() || null,
        page: this.page$(),
        size: 20,
      })}`,
  );

  protected me(): string | undefined {
    return this.auth.user()?.id;
  }

  protected roleLabel(role: string): string {
    return { CUSTOMER: 'Customer', GENIE: 'Genie', ADMIN: 'Admin' }[role] ?? role;
  }

  protected async setStatus(u: UserRow, status: 'ACTIVE' | 'SUSPENDED'): Promise<void> {
    const suspend = status === 'SUSPENDED';
    const { confirmed } = await this.confirm.ask({
      title: suspend ? `Suspend ${u.fullName}?` : `Reactivate ${u.fullName}?`,
      message: suspend ? 'They are signed out on every device and cannot log in until reactivated.' : 'They can log in again.',
      confirmLabel: suspend ? 'Suspend' : 'Reactivate',
      tone: suspend ? 'danger' : 'primary',
    });
    if (!confirmed) return;
    this.api.setUserStatus(u.id, status).subscribe({
      next: () => {
        this.toast.success(suspend ? 'User suspended' : 'User reactivated');
        this.list.reload();
      },
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }

  protected readonly SearchIcon = SearchIcon;
  protected readonly Users = Users;
}
