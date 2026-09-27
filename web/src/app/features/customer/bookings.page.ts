import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { LucideCalendarDays as CalendarDays } from '@lucide/angular';

import { API, query } from '../../core/api/api';
import { BookingSummary, Page } from '../../core/api/models';
import { BookingRow } from '../../shared/ui/booking-parts';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';

type Scope = 'ACTIVE' | 'PAST' | 'ALL';

@Component({
  selector: 'pg-bookings-page',
  imports: [RouterLink, BookingRow, EmptyState, PageHead, Pager, Skeleton, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="My bookings" subtitle="Track requests, share your start code and pay after the job.">
      <a routerLink="/services" class="btn-primary">Book a service</a>
    </pg-page-head>

    <pg-tabs class="mt-6" [items]="tabs" [value]="scope()" label="Booking filter" (valueChange)="setScope($any($event))" />

    <div class="mt-5">
      @if (list.value(); as page) {
        <div class="space-y-3">
          @for (b of page.items; track b.id) {
            <pg-booking-row [booking]="b" linkBase="/account/bookings" counterpartLabel="with" />
          } @empty {
            <pg-empty
              [icon]="CalendarDays"
              [title]="scope() === 'ACTIVE' ? 'No upcoming bookings' : 'No bookings yet'"
              message="Pick a service, choose a verified Genie and a time that suits you."
            >
              <a routerLink="/services" class="btn-gold">Browse services</a>
            </pg-empty>
          }
        </div>
        <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="page$.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="4" />
      } @else if (list.error()) {
        <pg-empty title="Could not load your bookings" message="Please check your connection and try again.">
          <button type="button" class="btn-ghost" (click)="list.reload()">Retry</button>
        </pg-empty>
      }
    </div>
  `,
})
export default class BookingsPage {
  protected readonly scope = signal<Scope>('ACTIVE');
  protected readonly page$ = signal(0);
  protected readonly tabs: TabItem<Scope>[] = [
    { value: 'ACTIVE', label: 'Upcoming' },
    { value: 'PAST', label: 'Past' },
    { value: 'ALL', label: 'All' },
  ];

  protected readonly list = httpResource<Page<BookingSummary>>(() => `${API}/bookings${query({ scope: this.scope(), page: this.page$(), size: 10 })}`);

  protected setScope(scope: Scope): void {
    this.scope.set(scope);
    this.page$.set(0);
  }

  protected readonly CalendarDays = CalendarDays;
}
