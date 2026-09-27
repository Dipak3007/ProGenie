import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import {
  LucideBanknote as Banknote,
  LucideCalendarDays as CalendarDays,
  LucideIndianRupee as Rupee,
  LucideReceipt as Receipt,
  LucideWallet as WalletIcon,
} from '@lucide/angular';
import { DayPipe } from '../../shared/ui/day.pipe';

import { API, query } from '../../core/api/api';
import { LedgerLine, Page, Payout, Wallet } from '../../core/api/models';
import { inr } from '../../shared/format';
import { EmptyState, PageHead, Pager, StatTile, TabItem, Tabs } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';

const ACCOUNT_TEXT: Record<string, string> = {
  GENIE_EARNINGS: 'Job earnings',
  TIPS: 'Tip',
  GENIE_CASH: 'Cash you collected',
  PAYOUT: 'Payout to your UPI',
  SETTLEMENT: 'Commission settled',
};

@Component({
  selector: 'pg-wallet-page',
  imports: [DayPipe, CurrencyPipe, DatePipe, EmptyState, PageHead, Pager, StatTile, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Earnings" subtitle="Every rupee, recorded. Payouts go to your UPI ID every Monday." />

    @if (wallet.value(); as w) {
      <div class="mt-6 grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4">
        <pg-stat [dark]="true" label="Wallet balance" [value]="money(w.balance)" [hint]="w.balance < 0 ? 'You owe commission from cash jobs' : 'Owed to you'" [icon]="WalletIcon" />
        <pg-stat label="This week" [value]="money(w.earningsThisWeek)" [hint]="w.jobsThisWeek + ' jobs'" [icon]="Rupee" />
        <pg-stat label="Next payout" [value]="money(w.availableForPayout)" [hint]="w.pendingPayouts > 0 ? money(w.pendingPayouts) + ' already scheduled' : 'Paid on Monday'" [icon]="CalendarDays" />
        <pg-stat label="Lifetime" [value]="money(w.lifetimeEarnings)" [hint]="w.commissionDue > 0 ? money(w.commissionDue) + ' commission due' : 'Total earned'" [icon]="Banknote" />
      </div>
      @if (w.commissionDue > 0) {
        <p class="mt-4 rounded-2xl bg-amber-50 px-4 py-3 text-sm text-amber-900 ring-1 ring-amber-200">
          You collected cash for some jobs, so {{ w.commissionDue | currency: 'INR' }} of ProGenie's 5% commission is due. It's deducted from your next online earnings, or you can settle it with the team.
        </p>
      }
    } @else {
      <div class="mt-6 grid grid-cols-2 gap-3 xl:grid-cols-4">
        @for (i of [1, 2, 3, 4]; track i) {
          <div class="card h-28 animate-pulse"></div>
        }
      </div>
    }

    <pg-tabs class="mt-8" [items]="tabs" [value]="tab()" (valueChange)="tab.set($any($event))" />

    @if (tab() === 'entries') {
      <div class="mt-4">
        @if (entries.value(); as page) {
          @if (page.items.length) {
            <ul class="card divide-y divide-line overflow-hidden">
              @for (e of page.items; track e.id) {
                <li class="flex items-center gap-3 px-4 py-3 sm:px-5">
                  <div class="min-w-0 flex-1">
                    <p class="text-sm font-semibold">{{ label(e) }}</p>
                    <p class="truncate text-xs text-muted">{{ e.createdAt | date: 'd MMM, h:mm a' }}{{ e.bookingRef ? ' · ' + e.bookingRef : '' }}{{ e.description ? ' · ' + e.description : '' }}</p>
                  </div>
                  <p class="shrink-0 font-semibold tabular-nums" [class]="e.direction === 'C' ? 'text-emerald-700' : 'text-ink'">
                    {{ e.direction === 'C' ? '+' : '−' }} {{ e.amount | currency: 'INR' }}
                  </p>
                </li>
              }
            </ul>
            <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="entriesPage.set($event)" />
          } @else {
            <pg-empty [icon]="Receipt" title="No transactions yet" message="Completed jobs, tips and payouts appear here." />
          }
        }
      </div>
    } @else {
      <div class="mt-4">
        @if (payouts.value(); as list) {
          @if (list.length) {
            <div class="card overflow-x-auto">
              <table class="w-full min-w-[34rem] text-left text-sm">
                <thead class="bg-surface text-xs uppercase tracking-wider text-muted">
                  <tr>
                    <th class="px-4 py-3 font-semibold">Week</th>
                    <th class="px-4 py-3 font-semibold">Status</th>
                    <th class="px-4 py-3 font-semibold">Reference</th>
                    <th class="px-4 py-3 text-right font-semibold">Amount</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-line">
                  @for (p of list; track p.id) {
                    <tr>
                      <td class="px-4 py-3">{{ p.periodStart | pgDay }} – {{ p.periodEnd | pgDay }}</td>
                      <td class="px-4 py-3"><pg-status [status]="p.status" /></td>
                      <td class="px-4 py-3 text-muted">{{ p.reference ?? '—' }}{{ p.paidAt ? ' · ' + (p.paidAt | date: 'd MMM') : '' }}</td>
                      <td class="px-4 py-3 text-right font-semibold tabular-nums">{{ p.amount | currency: 'INR' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <pg-empty [icon]="CalendarDays" title="No payouts yet" message="Each Monday we pay out the online earnings of the past week." />
          }
        }
      </div>
    }
  `,
})
export default class WalletPage {
  protected readonly wallet = httpResource<Wallet>(() => `${API}/genie/wallet`);
  protected readonly tab = signal<'entries' | 'payouts'>('entries');
  protected readonly entriesPage = signal(0);
  protected readonly entries = httpResource<Page<LedgerLine>>(() => `${API}/genie/wallet/entries${query({ page: this.entriesPage(), size: 15 })}`);
  protected readonly payouts = httpResource<Payout[]>(() => (this.tab() === 'payouts' ? `${API}/genie/payouts` : undefined));
  protected readonly tabs: TabItem[] = [
    { value: 'entries', label: 'Transactions' },
    { value: 'payouts', label: 'Payouts' },
  ];
  protected readonly money = inr;
  protected readonly hasData = computed(() => !!this.wallet.value());

  protected label(e: LedgerLine): string {
    return ACCOUNT_TEXT[e.account] ?? e.account;
  }

  protected readonly Banknote = Banknote;
  protected readonly CalendarDays = CalendarDays;
  protected readonly Receipt = Receipt;
  protected readonly Rupee = Rupee;
  protected readonly WalletIcon = WalletIcon;
}
