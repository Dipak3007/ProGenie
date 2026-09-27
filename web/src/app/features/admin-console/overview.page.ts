import { ChangeDetectionStrategy, Component, computed, linkedSignal, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import {
  LucideCalendarDays as CalendarDays,
  LucideCircleCheck as CircleCheck,
  LucideDynamicIcon,
  LucideFlag as Flag,
  LucideIndianRupee as Rupee,
  LucidePercent as Percent,
  LucideReceipt as Receipt,
  LucideShieldCheck as ShieldCheck,
  LucideTriangleAlert as TriangleAlert,
  LucideUsers as Users,
} from '@lucide/angular';

import { API, query } from '../../core/api/api';
import { Overview } from '../../core/api/models';
import { addDays, inr, statusMeta, todayIst } from '../../shared/format';
import { BarItem, BarList, ChartPoint, ColumnChart } from '../../shared/ui/charts';
import { formatDay } from '../../shared/ui/day.pipe';
import { PageHead, StatTile, TabItem, Tabs } from '../../shared/ui/kit';

type Range = '7' | '30' | '90';

@Component({
  selector: 'pg-admin-overview-page',
  imports: [RouterLink, LucideDynamicIcon, BarList, ColumnChart, PageHead, StatTile, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Overview" [subtitle]="subtitle()" />

    <!-- Filters scope everything below -->
    <pg-tabs class="mt-5" [items]="ranges" [value]="range()" label="Date range" (valueChange)="range.set($any($event))" />

    @if (shown(); as o) {
      <div class="transition-opacity" [class.opacity-60]="data.isLoading()">
        <!-- Needs attention -->
        @if (o.pendingVerifications || o.flaggedGenies || o.outstandingFees) {
          <div class="mt-5 grid gap-3 sm:grid-cols-3">
            <a routerLink="/admin/genies" [queryParams]="{ status: 'UNDER_REVIEW' }" class="card flex items-center gap-3 p-4 hover:border-brand-soft">
              <svg [lucideIcon]="ShieldCheck" [size]="20" class="text-brand" aria-hidden="true"></svg>
              <span class="text-sm"><span class="font-display text-xl font-bold">{{ o.pendingVerifications }}</span> awaiting verification</span>
            </a>
            <a routerLink="/admin/genies" [queryParams]="{ flagged: true }" class="card flex items-center gap-3 p-4 hover:border-brand-soft">
              <svg [lucideIcon]="Flag" [size]="20" class="text-rose-600" aria-hidden="true"></svg>
              <span class="text-sm"><span class="font-display text-xl font-bold">{{ o.flaggedGenies }}</span> flagged Genies</span>
            </a>
            <a routerLink="/admin/bookings" class="card flex items-center gap-3 p-4 hover:border-brand-soft">
              <svg [lucideIcon]="TriangleAlert" [size]="20" class="text-amber-600" aria-hidden="true"></svg>
              <span class="text-sm"><span class="font-display text-xl font-bold">{{ o.outstandingFees }}</span> unpaid cancellation fees</span>
            </a>
          </div>
        }

        <div class="mt-5 grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4">
          <pg-stat [dark]="true" label="GMV" [value]="money(o.gmv)" [hint]="o.completed + ' completed jobs'" [icon]="Rupee" />
          <pg-stat label="Commission" [value]="money(o.commission)" hint="5% of service value" [icon]="Receipt" />
          <pg-stat label="Bookings" [value]="o.bookingsCreated" [hint]="'Avg order ' + money(o.avgOrderValue)" [icon]="CalendarDays" />
          <pg-stat label="Acceptance" [value]="pct(o.acceptanceRate)" [hint]="'Cancelled ' + pct(o.cancellationRate)" [icon]="Percent" />
          <pg-stat label="New customers" [value]="o.newCustomers" [icon]="Users" />
          <pg-stat label="New Genies" [value]="o.newGenies" [hint]="o.approvedGenies + ' approved in total'" [icon]="CircleCheck" />
        </div>

        <div class="mt-6 grid gap-6 xl:grid-cols-2">
          <section class="card p-5 sm:p-6" aria-labelledby="gmv-h">
            <h2 id="gmv-h" class="text-lg font-bold">GMV per day</h2>
            <p class="text-sm text-muted">Value of completed jobs, INR</p>
            <pg-column-chart class="mt-4" [points]="gmvSeries()" title="GMV" [format]="compactMoney" />
          </section>
          <section class="card p-5 sm:p-6" aria-labelledby="bk-h">
            <h2 id="bk-h" class="text-lg font-bold">Bookings per day</h2>
            <p class="text-sm text-muted">New booking requests</p>
            <pg-column-chart class="mt-4" [points]="bookingSeries()" title="Bookings" />
          </section>
        </div>

        <div class="mt-6 grid gap-6 lg:grid-cols-3">
          <section class="card p-5 sm:p-6" aria-labelledby="cat-h">
            <h2 id="cat-h" class="text-lg font-bold">Top categories</h2>
            <p class="mb-4 text-sm text-muted">By GMV</p>
            <pg-bar-list [items]="categories()" [format]="money" />
          </section>
          <section class="card p-5 sm:p-6" aria-labelledby="gen-h">
            <h2 id="gen-h" class="text-lg font-bold">Top Genies</h2>
            <p class="mb-4 text-sm text-muted">By completed jobs</p>
            <pg-bar-list [items]="genies()" />
          </section>
          <section class="card p-5 sm:p-6" aria-labelledby="st-h">
            <h2 id="st-h" class="text-lg font-bold">Bookings by status</h2>
            <p class="mb-4 text-sm text-muted">Created in this period</p>
            <pg-bar-list [items]="statuses()" />
          </section>
        </div>
      </div>
    } @else if (data.isLoading()) {
      <div class="mt-5 grid grid-cols-2 gap-3 xl:grid-cols-4">
        @for (i of [1, 2, 3, 4]; track i) {
          <div class="card h-28 animate-pulse"></div>
        }
      </div>
    }
  `,
})
export default class AdminOverviewPage {
  protected readonly range = signal<Range>('30');
  protected readonly ranges: TabItem<Range>[] = [
    { value: '7', label: 'Last 7 days' },
    { value: '30', label: 'Last 30 days' },
    { value: '90', label: 'Last 90 days' },
  ];

  protected readonly data = httpResource<Overview>(() => {
    const to = todayIst();
    return `${API}/admin/analytics/overview${query({ from: addDays(to, 1 - Number(this.range())), to })}`;
  });

  /** Keeps the previous numbers on screen (dimmed) while a new range loads: no skeleton flash, no layout jump. */
  protected readonly shown = linkedSignal<Overview | undefined, Overview | undefined>({
    source: () => this.data.value(),
    computation: (value, previous) => value ?? previous?.value,
  });

  protected readonly money = inr;
  protected readonly compactMoney = (v: number) => (v >= 1000 ? `₹${(v / 1000).toFixed(v >= 10000 ? 0 : 1)}k` : `₹${Math.round(v)}`);

  protected pct(v: number | null | undefined): string {
    return v === null || v === undefined ? '—' : `${v}%`;
  }

  protected readonly subtitle = computed(() => {
    const o = this.shown();
    return o ? `${formatDay(o.from, 'd MMM y')} – ${formatDay(o.to, 'd MMM y')} · Ahmedabad` : 'Marketplace health at a glance';
  });
  protected readonly gmvSeries = computed<ChartPoint[]>(() => (this.shown()?.daily ?? []).map((d) => ({ label: d.day, value: d.gmv })));
  protected readonly bookingSeries = computed<ChartPoint[]>(() => (this.shown()?.daily ?? []).map((d) => ({ label: d.day, value: d.bookings })));
  protected readonly categories = computed<BarItem[]>(() =>
    (this.shown()?.topCategories ?? []).map((c) => ({ label: c.name, value: c.gmv, hint: `${c.completed} jobs` })),
  );
  protected readonly genies = computed<BarItem[]>(() =>
    (this.shown()?.topGenies ?? []).map((g) => ({ label: g.name, value: g.completed, hint: `${g.avgRating}★ · ${inr(g.earnings)}` })),
  );
  protected readonly statuses = computed<BarItem[]>(() =>
    Object.entries(this.shown()?.bookingsByStatus ?? {})
      .map(([status, count]) => ({ label: statusMeta(status).label, value: count }))
      .sort((a, b) => b.value - a.value),
  );

  protected readonly CalendarDays = CalendarDays;
  protected readonly CircleCheck = CircleCheck;
  protected readonly Flag = Flag;
  protected readonly Percent = Percent;
  protected readonly Receipt = Receipt;
  protected readonly Rupee = Rupee;
  protected readonly ShieldCheck = ShieldCheck;
  protected readonly TriangleAlert = TriangleAlert;
  protected readonly Users = Users;
}
