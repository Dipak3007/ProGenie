import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { LucideBell as Bell } from '@lucide/angular';

import { API, query } from '../../core/api/api';
import { AppNotification, Page } from '../../core/api/models';
import { NotificationStore } from '../../core/notifications/notification-store';
import { ToastService } from '../../core/ui/feedback';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';

@Component({
  selector: 'pg-notifications-page',
  imports: [DatePipe, EmptyState, PageHead, Pager, Skeleton, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container-page max-w-3xl pb-20 pt-8 sm:pt-10">
      <pg-page-head title="Notifications" subtitle="Booking updates, payments and account news.">
        <button type="button" class="btn-ghost" [disabled]="!store.unread()" (click)="markAll()">Mark all read</button>
      </pg-page-head>

      <pg-tabs class="mt-6" [items]="tabs()" [value]="filter()" (valueChange)="setFilter($event)" />

      <div class="mt-5">
        @if (list.value(); as page) {
          <ul class="card divide-y divide-line overflow-hidden">
            @for (n of page.items; track n.id) {
              <li>
                <button type="button" class="flex w-full gap-3 px-4 py-4 text-left hover:bg-surface sm:px-5" (click)="open(n)">
                  <span class="mt-1.5 size-2.5 shrink-0 rounded-full" [class]="n.readAt ? 'bg-line' : 'bg-brand'" aria-hidden="true"></span>
                  <span class="min-w-0 flex-1">
                    <span class="flex flex-wrap items-baseline justify-between gap-x-3">
                      <span class="font-semibold" [class.text-muted]="n.readAt">{{ n.title }}</span>
                      <span class="text-xs text-muted">{{ n.createdAt | date: 'd MMM, h:mm a' }}</span>
                    </span>
                    @if (n.body) {
                      <span class="mt-1 block text-sm text-ink/75">{{ n.body }}</span>
                    }
                    @if (!n.readAt) {
                      <span class="sr-only">Unread</span>
                    }
                  </span>
                </button>
              </li>
            } @empty {
              <li class="p-2">
                <pg-empty [icon]="Bell" [title]="filter() === 'unread' ? 'No unread notifications' : 'Nothing here yet'" message="We'll let you know when something happens." />
              </li>
            }
          </ul>
          <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="page$.set($event)" />
        } @else if (list.isLoading()) {
          <pg-skeleton [rows]="4" />
        }
      </div>
    </div>
  `,
})
export default class NotificationsPage {
  protected readonly store = inject(NotificationStore);
  private readonly toast = inject(ToastService);
  protected readonly filter = signal<string>('all');
  protected readonly page$ = signal(0);
  protected readonly tabs = computed<TabItem[]>(() => [
    { value: 'all', label: 'All' },
    { value: 'unread', label: 'Unread', count: this.store.unread() },
  ]);

  protected readonly list = httpResource<Page<AppNotification>>(
    () => `${API}/notifications${query({ unreadOnly: this.filter() === 'unread', page: this.page$(), size: 20 })}`,
  );

  protected setFilter(value: string): void {
    this.filter.set(value);
    this.page$.set(0);
  }

  protected open(n: AppNotification): void {
    this.store.open(n);
    if (!n.link) this.list.reload();
  }

  protected markAll(): void {
    this.store.markAllRead().subscribe(() => {
      this.toast.success('All caught up');
      this.list.reload();
    });
  }

  protected readonly Bell = Bell;
}
