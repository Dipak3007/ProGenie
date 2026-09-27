import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { LucideBriefcase as Briefcase } from '@lucide/angular';

import { API, query } from '../../core/api/api';
import { BookingSummary, Page } from '../../core/api/models';
import { BookingRow } from '../../shared/ui/booking-parts';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';
import { GenieStore } from './genie-store';

type Scope = 'REQUESTS' | 'UPCOMING' | 'PAST' | 'ALL';

const EMPTY: Record<Scope, { title: string; message: string }> = {
  REQUESTS: { title: 'No requests right now', message: 'New booking requests appear here. Reply within 30 minutes or they expire.' },
  UPCOMING: { title: 'No upcoming jobs', message: 'Accepted jobs and jobs in progress show up here.' },
  PAST: { title: 'No past jobs yet', message: 'Completed, cancelled and expired jobs are kept here.' },
  ALL: { title: 'No jobs yet', message: 'Once customers book you, every job is listed here.' },
};

@Component({
  selector: 'pg-genie-jobs-page',
  imports: [BookingRow, EmptyState, PageHead, Pager, Skeleton, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Jobs" subtitle="Requests to answer, today's work and your history." />
    <pg-tabs class="mt-6" [items]="tabs()" [value]="scope()" label="Job filter" (valueChange)="setScope($any($event))" />
    <div class="mt-5">
      @if (list.value(); as page) {
        <div class="space-y-3">
          @for (b of page.items; track b.id) {
            <pg-booking-row [booking]="b" linkBase="/genie/bookings" audience="GENIE" counterpartLabel="for" />
          } @empty {
            <pg-empty [icon]="Briefcase" [title]="empty[scope()].title" [message]="empty[scope()].message" />
          }
        </div>
        <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="page$.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="4" />
      }
    </div>
  `,
})
export default class GenieJobsPage {
  private readonly store = inject(GenieStore);
  protected readonly scope = signal<Scope>(this.store.requests() > 0 ? 'REQUESTS' : 'UPCOMING');
  protected readonly page$ = signal(0);
  protected readonly empty = EMPTY;
  protected readonly tabs = computed<TabItem<Scope>[]>(() => [
    { value: 'REQUESTS', label: 'Requests', count: this.store.requests() },
    { value: 'UPCOMING', label: 'Upcoming' },
    { value: 'PAST', label: 'Past' },
    { value: 'ALL', label: 'All' },
  ]);
  protected readonly list = httpResource<Page<BookingSummary>>(() => `${API}/genie/bookings${query({ scope: this.scope(), page: this.page$(), size: 10 })}`);

  protected setScope(scope: Scope): void {
    this.scope.set(scope);
    this.page$.set(0);
  }

  protected readonly Briefcase = Briefcase;
}
