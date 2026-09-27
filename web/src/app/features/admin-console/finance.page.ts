import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LucideBanknote as Banknote } from '@lucide/angular';
import { DayPipe } from '../../shared/ui/day.pipe';

import { API, errorMessage, query } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { Page, Payout } from '../../core/api/models';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { inr } from '../../shared/format';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, PageHead, Pager, Skeleton, Spinner, TabItem, Tabs } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';

@Component({
  selector: 'pg-admin-finance-page',
  imports: [DayPipe, RouterLink, CurrencyPipe, DatePipe, FormsModule, Dialog, EmptyState, PageHead, Pager, Skeleton, Spinner, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Finance" subtitle="Weekly Genie payouts. Created automatically every Monday 06:00; pay by UPI, then record the reference.">
      <button type="button" class="btn-ghost" [disabled]="generating()" (click)="generate()">
        @if (generating()) {
          <pg-spinner />
        }
        Generate payouts now
      </button>
    </pg-page-head>

    <pg-tabs class="mt-5" [items]="tabs" [value]="status()" label="Payout status" (valueChange)="status.set($event); page$.set(0)" />

    <div class="mt-5">
      @if (list.value(); as page) {
        @if (page.items.length) {
          <p class="mb-3 text-sm text-muted">{{ page.total }} payout{{ page.total === 1 ? '' : 's' }}{{ status() === 'PENDING' ? ' · ' + money(pendingTotal()) + ' on this page to transfer' : '' }}</p>
          <ul class="space-y-2">
            @for (p of page.items; track p.id) {
              <li class="card flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
                <div class="min-w-0 flex-1">
                  <p class="flex flex-wrap items-center gap-2 font-semibold">
                    <a [routerLink]="['/admin/genies', p.genieId]" class="hover:text-brand">{{ p.genieName }}</a> <pg-status [status]="p.status" />
                  </p>
                  <p class="truncate text-sm text-muted">
                    Week {{ p.periodStart | pgDay }} – {{ p.periodEnd | pgDay }} · UPI {{ p.upiId ?? 'not set' }}
                    {{ p.reference ? ' · ref ' + p.reference : '' }}{{ p.paidAt ? ' · paid ' + (p.paidAt | date: 'd MMM') : '' }}
                  </p>
                </div>
                <p class="font-display text-xl font-bold tabular-nums">{{ p.amount | currency: 'INR' }}</p>
                @if (p.status === 'PENDING') {
                  <div class="flex gap-2">
                    <button type="button" class="btn-primary min-h-10 px-4" (click)="openPaid(p)">Mark paid</button>
                    <button type="button" class="btn-ghost min-h-10 px-4" (click)="markFailed(p)">Failed</button>
                  </div>
                }
              </li>
            }
          </ul>
          <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="page$.set($event)" />
        } @else {
          <pg-empty [icon]="Banknote" [title]="status() === 'PENDING' ? 'No payouts waiting' : 'No payouts'" message="Payouts appear here after the Monday run (or when you generate them)." />
        }
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="4" />
      }
    </div>

    @let p = paying();
    <pg-dialog [open]="!!p" [heading]="p ? 'Pay ' + p.genieName : ''" [subheading]="p ? money(p.amount) + ' to ' + (p.upiId ?? 'their UPI ID') : null" size="sm" (closed)="paying.set(null)">
      <form class="space-y-4" (ngSubmit)="markPaid()">
        <p class="text-sm text-muted">Send the money from the ProGenie bank account first, then paste the UPI transaction reference here.</p>
        <div>
          <label class="label" for="ref">UPI / bank reference</label>
          <input id="ref" name="ref" class="field" maxlength="100" required [(ngModel)]="reference" placeholder="e.g. 425613987654" />
        </div>
        <div class="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" class="btn-ghost" (click)="paying.set(null)">Cancel</button>
          <button type="submit" class="btn-primary" [disabled]="busy() || !reference.trim()">Mark as paid</button>
        </div>
      </form>
    </pg-dialog>
  `,
})
export default class AdminFinancePage {
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly status = signal('PENDING');
  protected readonly page$ = signal(0);
  protected readonly tabs: TabItem[] = [
    { value: 'PENDING', label: 'To pay' },
    { value: 'PAID', label: 'Paid' },
    { value: 'FAILED', label: 'Failed' },
    { value: 'ALL', label: 'All' },
  ];
  protected readonly list = httpResource<Page<Payout>>(
    () => `${API}/admin/payouts${query({ status: this.status() === 'ALL' ? null : this.status(), page: this.page$(), size: 20 })}`,
  );
  protected readonly pendingTotal = computed(() => (this.list.value()?.items ?? []).reduce((sum, p) => sum + Number(p.amount), 0));

  protected readonly generating = signal(false);
  protected readonly busy = signal(false);
  protected readonly paying = signal<Payout | null>(null);
  protected reference = '';
  protected readonly money = inr;

  protected async generate(): Promise<void> {
    const { confirmed } = await this.confirm.ask({
      title: 'Generate payouts now?',
      message: 'Creates a pending payout for last week (Monday–Sunday) for every Genie whose wallet balance is positive and not already scheduled.',
      confirmLabel: 'Generate',
    });
    if (!confirmed) return;
    this.generating.set(true);
    this.api.generatePayouts(null).subscribe({
      next: (res) => {
        this.generating.set(false);
        this.toast.success(res.created ? `${res.created} payout${res.created === 1 ? '' : 's'} created` : 'Nothing new to pay out');
        this.list.reload();
      },
      error: (err) => {
        this.generating.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected openPaid(p: Payout): void {
    this.reference = '';
    this.paying.set(p);
  }

  protected markPaid(): void {
    const p = this.paying();
    if (!p) return;
    this.busy.set(true);
    this.api.markPaid(p.id, this.reference.trim()).subscribe({
      next: () => {
        this.busy.set(false);
        this.paying.set(null);
        this.toast.success(`Payout to ${p.genieName} recorded`);
        this.list.reload();
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected async markFailed(p: Payout): Promise<void> {
    const { confirmed, value } = await this.confirm.ask({
      title: `Mark the payout to ${p.genieName} as failed?`,
      message: "The money stays in their wallet and is included in next week's payout.",
      confirmLabel: 'Mark failed',
      tone: 'danger',
      input: { label: 'Reason', placeholder: 'e.g. UPI ID does not exist', required: true },
    });
    if (!confirmed) return;
    this.api.markFailed(p.id, value).subscribe({
      next: () => {
        this.toast.success('Marked as failed');
        this.list.reload();
      },
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }

  protected readonly Banknote = Banknote;
}
