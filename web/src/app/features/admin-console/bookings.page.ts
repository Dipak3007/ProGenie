import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { LucideCalendarDays as CalendarDays, LucideDynamicIcon, LucideSearch as SearchIcon } from '@lucide/angular';

import { API, query } from '../../core/api/api';
import { BookingSummary, Page } from '../../core/api/models';
import { addDays, statusMeta } from '../../shared/format';
import { BookingRow } from '../../shared/ui/booking-parts';
import { EmptyState, PageHead, Pager, Skeleton } from '../../shared/ui/kit';

const STATUSES = ['REQUESTED', 'ACCEPTED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'REJECTED', 'EXPIRED'];

@Component({
  selector: 'pg-admin-bookings-page',
  imports: [FormsModule, LucideDynamicIcon, BookingRow, EmptyState, PageHead, Pager, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Bookings" subtitle="Every booking on the platform. Search by reference, name or phone." />

    <form class="card mt-5 grid gap-3 p-4 sm:grid-cols-2 lg:grid-cols-[1.4fr_1fr_1fr_1fr_auto] lg:items-end" (ngSubmit)="apply()">
      <div class="sm:col-span-2 lg:col-span-1">
        <label class="label" for="b-q">Search</label>
        <div class="relative">
          <svg [lucideIcon]="SearchIcon" [size]="16" class="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" aria-hidden="true"></svg>
          <input id="b-q" name="q" type="search" class="field py-2.5 pl-10" placeholder="PG-2026-… , name or phone" [(ngModel)]="form.q" />
        </div>
      </div>
      <div>
        <label class="label" for="b-status">Status</label>
        <select id="b-status" name="status" class="field py-2.5" [(ngModel)]="form.status">
          <option value="">Any status</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ label(s) }}</option>
          }
        </select>
      </div>
      <div>
        <label class="label" for="b-from">Slot from</label>
        <input id="b-from" name="from" type="date" class="field py-2.5" [(ngModel)]="form.from" />
      </div>
      <div>
        <label class="label" for="b-to">Slot to</label>
        <input id="b-to" name="to" type="date" class="field py-2.5" [(ngModel)]="form.to" />
      </div>
      <div class="flex gap-2 sm:col-span-2 lg:col-span-1">
        <button type="submit" class="btn-primary flex-1">Apply</button>
        <button type="button" class="btn-ghost" (click)="reset()">Reset</button>
      </div>
    </form>

    <div class="mt-5">
      @if (list.value(); as page) {
        <p class="mb-3 text-sm text-muted">{{ page.total }} booking{{ page.total === 1 ? '' : 's' }}</p>
        <div class="space-y-3">
          @for (b of page.items; track b.id) {
            <pg-booking-row [booking]="b" linkBase="/admin/bookings" audience="ADMIN" counterpartLabel="" />
          } @empty {
            <pg-empty [icon]="CalendarDays" title="No bookings match" message="Try another status or date range." />
          }
        </div>
        <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="page$.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="5" />
      }
    </div>
  `,
})
export default class AdminBookingsPage {
  protected readonly statuses = STATUSES;
  protected form = { q: '', status: '', from: '', to: '' };
  private readonly filters = signal({ q: '', status: '', from: '', to: '' });
  protected readonly page$ = signal(0);

  protected readonly list = httpResource<Page<BookingSummary>>(() => {
    const f = this.filters();
    return `${API}/admin/bookings${query({
      q: f.q || null,
      status: f.status || null,
      // Dates are India days: [from 00:00 IST, to+1 00:00 IST)
      from: f.from ? `${f.from}T00:00:00+05:30` : null,
      to: f.to ? `${addDays(f.to, 1)}T00:00:00+05:30` : null,
      page: this.page$(),
      size: 15,
    })}`;
  });

  protected label(status: string): string {
    return statusMeta(status).label;
  }

  protected apply(): void {
    this.filters.set({ ...this.form, q: this.form.q.trim() });
    this.page$.set(0);
  }

  protected reset(): void {
    this.form = { q: '', status: '', from: '', to: '' };
    this.apply();
  }

  protected readonly CalendarDays = CalendarDays;
  protected readonly SearchIcon = SearchIcon;
}
