import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LucideAlarmClock as AlarmClock, LucideArrowLeft as ArrowLeft, LucideDynamicIcon } from '@lucide/angular';

import { AdminApi } from '../../core/api/admin-api';
import { API, errorMessage } from '../../core/api/api';
import { BookingDetail, Payment, RefundRequest, Slot, TicketAction, TicketDetail } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth-store';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, Skeleton, Spinner } from '../../shared/ui/kit';
import { SlotPicker } from '../../shared/ui/slot-picker';
import { TicketThread } from '../support/ticket-thread';
import { AdminCounts } from './admin-console.page';
import { RefundFields, newRefund, refundValid } from './refund-fields';

const OPEN = ['OPEN', 'IN_REVIEW', 'AWAITING_REPLY'];

@Component({
  selector: 'pg-admin-ticket-page',
  imports: [CurrencyPipe, DatePipe, FormsModule, RouterLink, LucideDynamicIcon, Dialog, EmptyState, RefundFields, Skeleton, SlotPicker, Spinner, TicketThread],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/admin/tickets" class="inline-flex items-center gap-1 text-sm font-semibold text-muted hover:text-brand">
      <svg [lucideIcon]="ArrowLeft" [size]="16" aria-hidden="true"></svg> Complaints
    </a>

    @if (ticket.value(); as t) {
      <h1 class="mt-3 text-2xl font-bold sm:text-3xl">{{ t.subject }}</h1>
      <p class="mt-1 text-sm text-muted">
        {{ t.ticketRef }}
        @if (t.bookingId) {
          · <a [routerLink]="['/admin/bookings', t.bookingId]" class="font-semibold text-brand hover:underline">{{ t.bookingRef }}</a> · {{ t.serviceName }}
        } @else {
          · privacy request
        }
      </p>

      <div class="mt-6 grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div class="min-w-0 space-y-6">
          <pg-ticket-thread [ticket]="t" [adminMode]="true" (changed)="ticket.set($event)" />

          @if (isOpen(t)) {
            <form class="card p-5" (ngSubmit)="reply(t)">
              <label class="label" for="admin-reply">{{ internal ? 'Internal note (only admins see it)' : 'Reply to the customer and Genie' }}</label>
              <textarea
                id="admin-reply"
                name="reply"
                class="field min-h-28"
                [class.bg-amber-50]="internal"
                maxlength="2000"
                [(ngModel)]="draft"
                [placeholder]="internal ? 'Notes for the team' : 'Both sides will see this'"
              ></textarea>
              <div class="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2 text-sm">
                <label class="flex items-center gap-2"><input type="checkbox" name="internal" class="size-4 accent-amber-600" [(ngModel)]="internal" /> Internal note</label>
                @if (!internal) {
                  <label class="flex items-center gap-2"><input type="checkbox" name="await" class="size-4 accent-brand" [(ngModel)]="awaitReply" /> Wait for their answer</label>
                }
                <button type="submit" class="btn-primary ml-auto" [disabled]="!draft.trim() || busy()">{{ internal ? 'Save note' : 'Send reply' }}</button>
              </div>
            </form>
          }
        </div>

        <aside class="space-y-6 xl:sticky xl:top-[calc(var(--header-h)+2.5rem)] xl:self-start">
          <section class="card space-y-3 p-5 text-sm">
            <h2 class="text-lg font-bold">Deadlines</h2>
            @if (isOpen(t)) {
              <p [class]="t.overdue ? 'font-semibold text-rose-700' : ''">
                @if (t.overdue) {
                  <svg [lucideIcon]="AlarmClock" [size]="14" class="inline" aria-hidden="true"></svg>
                }
                First reply {{ t.firstRespondedAt ? 'sent ' + (t.firstRespondedAt | date: 'd MMM, h:mm a') : 'due ' + (t.firstResponseDue | date: 'd MMM, h:mm a') }}
              </p>
              <p>Resolve by {{ t.resolutionDue | date: 'd MMM, h:mm a' }}</p>
            } @else {
              <p>{{ t.status === 'CLOSED' ? 'Closed' : 'Resolved' }} {{ (t.closedAt ?? t.resolvedAt) | date: 'd MMM, h:mm a' }}</p>
              @if (t.resolutionActions) {
                <p class="text-muted">Actions: {{ t.resolutionActions }}</p>
              }
            }
            <p class="border-t border-line pt-3">
              {{ t.assignedToName ? 'With ' + t.assignedToName : 'Unassigned' }}
              @if (isOpen(t) && t.assignedTo !== auth.user()?.id) {
                · <button type="button" class="font-semibold text-brand hover:underline" [disabled]="busy()" (click)="assign(t)">Take it</button>
              }
            </p>
          </section>

          @if (t.customerName || t.genieName) {
            <section class="card space-y-2 p-5 text-sm">
              <h2 class="text-lg font-bold">People</h2>
              @if (t.customerName) {
                <p>Customer: <span class="font-semibold">{{ t.customerName }}</span></p>
              }
              @if (t.genieId) {
                <p>Genie: <a [routerLink]="['/admin/genies', t.genieId]" class="font-semibold text-brand hover:underline">{{ t.genieName }}</a></p>
              }
              <p class="text-muted">Raised by the {{ t.raisedByRole === 'GENIE' ? 'Genie' : 'customer' }}.</p>
            </section>
          }

          @if (isOpen(t)) {
            <section class="card space-y-2 p-5">
              <h2 class="text-lg font-bold">Decide</h2>
              <button type="button" class="btn-primary w-full" (click)="openResolve()">Resolve…</button>
              <button type="button" class="btn-ghost w-full" [disabled]="busy()" (click)="reject(t)">Close without action…</button>
            </section>
          }
        </aside>
      </div>

      <pg-dialog [open]="resolveOpen()" heading="Resolve the complaint" [subheading]="t.ticketRef" size="lg" (closed)="resolveOpen.set(false)">
        @if (resolveOpen()) {
          <form (ngSubmit)="resolve(t)">
            <fieldset>
              <legend class="label">What will you do? (combine as needed)</legend>
              <div class="grid gap-2 sm:grid-cols-2">
                @for (a of actionOptions(t); track a.value) {
                  <label class="flex cursor-pointer items-start gap-3 rounded-2xl border p-3 text-sm" [class]="has(a.value) ? 'border-brand bg-brand-mist/50' : 'border-line'">
                    <input type="checkbox" class="mt-0.5 size-4 accent-brand" [checked]="has(a.value)" (change)="toggle(a.value)" />
                    <span><span class="block font-semibold">{{ a.label }}</span><span class="text-xs text-muted">{{ a.hint }}</span></span>
                  </label>
                }
              </div>
            </fieldset>

            @if (has('REFUND')) {
              <div class="mt-5 rounded-2xl border border-line p-4">
                <p class="font-semibold">Refund</p>
                @if (refundablePayments().length > 1) {
                  <label class="label mt-3" for="r-pay">Payment</label>
                  <select id="r-pay" name="payment" class="field" [ngModel]="paymentId()" (ngModelChange)="pickPayment($event)">
                    @for (p of refundablePayments(); track p.id) {
                      <option [value]="p.id">{{ p.purpose === 'CANCELLATION_FEE' ? 'Cancellation fee' : 'Booking' }} · {{ p.method }} · {{ p.amount | currency: 'INR' }}</option>
                    }
                  </select>
                }
                @if (selectedPayment(); as p) {
                  <pg-refund-fields class="mt-3 block" [payment]="p" [(value)]="refund" [showReason]="false" />
                } @else {
                  <p class="mt-2 text-sm text-rose-700">This booking has no payment left to refund.</p>
                }
              </div>
            }

            @if (has('REDO') && booking.value(); as b) {
              <div class="mt-5 rounded-2xl border border-line p-4">
                <p class="font-semibold">Free redo with {{ b.genie.name }}</p>
                <p class="text-xs text-muted">Nothing to pay, no commission. The Genie confirms the time like any request.</p>
                <pg-slot-picker class="mt-3 block" [genieId]="b.genie.id" [serviceId]="b.serviceId" [selected]="redoSlot()?.start ?? null" (picked)="redoSlot.set($event)" />
              </div>
            }

            @if (has('STRIKE')) {
              <label class="label mt-5" for="r-strike">Warning for {{ t.genieName }} (optional, defaults to the note)</label>
              <input id="r-strike" name="strike" class="field" maxlength="300" [(ngModel)]="strikeReason" placeholder="e.g. Arrived 2 hours late without calling" />
              <p class="mt-1 text-xs text-muted">3 warnings in 90 days flag the Genie for review.</p>
            }

            <label class="label mt-5" for="r-note">Note to the customer and Genie</label>
            <textarea id="r-note" name="note" class="field min-h-24" required maxlength="1000" [(ngModel)]="note" placeholder="What you decided and why"></textarea>

            @if (resolveError()) {
              <p class="mt-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ resolveError() }}</p>
            }
            <div class="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <button type="button" class="btn-ghost" (click)="resolveOpen.set(false)">Cancel</button>
              <button type="submit" class="btn-primary" [disabled]="!canResolve() || busy()">
                @if (busy()) {
                  <pg-spinner />
                }
                Resolve
              </button>
            </div>
          </form>
        }
      </pg-dialog>
    } @else if (ticket.isLoading()) {
      <pg-skeleton class="mt-6 block" [rows]="3" />
    } @else {
      <pg-empty class="mt-6 block" title="Complaint not found">
        <a routerLink="/admin/tickets" class="btn-ghost">Back to complaints</a>
      </pg-empty>
    }
  `,
})
export default class AdminTicketPage {
  readonly id = input.required<string>();

  protected readonly auth = inject(AuthStore);
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly counts = inject(AdminCounts);

  protected readonly ticket = httpResource<TicketDetail>(() => `${API}/admin/tickets/${this.id()}`);
  private readonly bookingId = computed(() => this.ticket.value()?.bookingId ?? null);
  protected readonly booking = httpResource<BookingDetail>(() => (this.bookingId() ? `${API}/admin/bookings/${this.bookingId()}` : undefined));
  private readonly payments = httpResource<Payment[]>(() => (this.bookingId() ? `${API}/admin/bookings/${this.bookingId()}/payments` : undefined));
  protected readonly refundablePayments = computed(() =>
    (this.payments.value() ?? []).filter((p) => p.status === 'SUCCEEDED' && p.amount - (p.refundedAmount ?? 0) > 0),
  );

  protected readonly busy = signal(false);
  protected draft = '';
  protected internal = false;
  protected awaitReply = false;

  protected readonly resolveOpen = signal(false);
  protected readonly resolveError = signal<string | null>(null);
  protected readonly actions = signal<TicketAction[]>([]);
  protected readonly paymentId = signal<string | null>(null);
  protected readonly selectedPayment = computed(() => this.refundablePayments().find((p) => p.id === this.paymentId()) ?? null);
  protected readonly refund = signal<RefundRequest>({ amount: null, liability: 'GENIE' });
  protected readonly redoSlot = signal<Slot | null>(null);
  protected strikeReason = '';
  protected note = '';

  protected isOpen(t: TicketDetail): boolean {
    return OPEN.includes(t.status);
  }

  protected actionOptions(t: TicketDetail): { value: TicketAction; label: string; hint: string }[] {
    if (!t.bookingId) return [{ value: 'NO_ACTION', label: 'Answer only', hint: 'Privacy requests are answered with a note' }];
    return [
      { value: 'REFUND', label: 'Refund', hint: 'Full or partial; choose who pays' },
      { value: 'REDO', label: 'Free redo', hint: 'A new booking with nothing to pay' },
      { value: 'STRIKE', label: 'Warn the Genie', hint: 'Adds a strike' },
      { value: 'NO_ACTION', label: 'No action', hint: 'Explain why in the note' },
    ];
  }

  protected has(a: TicketAction): boolean {
    return this.actions().includes(a);
  }

  protected toggle(a: TicketAction): void {
    this.actions.update((list) => {
      if (list.includes(a)) return list.filter((x) => x !== a);
      return a === 'NO_ACTION' ? ['NO_ACTION'] : [...list.filter((x) => x !== 'NO_ACTION'), a];
    });
  }

  protected openResolve(): void {
    this.actions.set([]);
    this.note = '';
    this.strikeReason = '';
    this.redoSlot.set(null);
    this.resolveError.set(null);
    const first = this.refundablePayments()[0];
    this.paymentId.set(first?.id ?? null);
    if (first) this.refund.set(newRefund(first));
    this.resolveOpen.set(true);
  }

  protected pickPayment(id: string): void {
    this.paymentId.set(id);
    const p = this.selectedPayment();
    if (p) this.refund.set(newRefund(p));
  }

  protected canResolve(): boolean {
    if (!this.actions().length || !this.note.trim()) return false;
    if (this.has('REFUND')) {
      const p = this.selectedPayment();
      if (!p || !refundValid(this.refund(), p)) return false;
    }
    return !this.has('REDO') || !!this.redoSlot();
  }

  protected resolve(t: TicketDetail): void {
    if (!this.canResolve()) return;
    this.busy.set(true);
    this.resolveError.set(null);
    this.api
      .resolveTicket(t.id, {
        actions: this.actions(),
        note: this.note.trim(),
        paymentId: this.has('REFUND') ? this.paymentId() : null,
        refund: this.has('REFUND') ? { ...this.refund(), reason: `Complaint ${t.ticketRef}: ${t.subject}`.slice(0, 300) } : null,
        redo: this.has('REDO') && this.redoSlot() ? { slotStart: this.redoSlot()!.start } : null,
        strikeReason: this.has('STRIKE') ? this.strikeReason.trim() || null : null,
      })
      .subscribe({
        next: (updated) => {
          this.busy.set(false);
          this.resolveOpen.set(false);
          this.ticket.set(updated);
          this.payments.reload();
          this.counts.refresh();
          this.toast.success('Complaint resolved. Both sides were told.');
        },
        error: (err) => {
          this.busy.set(false);
          this.resolveError.set(errorMessage(err));
        },
      });
  }

  protected reply(t: TicketDetail): void {
    const text = this.draft.trim();
    if (!text) return;
    this.busy.set(true);
    this.api.replyTicket(t.id, text, this.internal, !this.internal && this.awaitReply).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.draft = '';
        this.awaitReply = false;
        this.ticket.set(updated);
        this.toast.success(this.internal ? 'Note saved' : 'Reply sent');
      },
      error: (err) => this.fail(err),
    });
  }

  protected assign(t: TicketDetail): void {
    this.busy.set(true);
    this.api.assignTicket(t.id).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.ticket.set(updated);
      },
      error: (err) => this.fail(err),
    });
  }

  protected async reject(t: TicketDetail): Promise<void> {
    const { confirmed, value } = await this.confirm.ask({
      title: 'Close without action?',
      message: 'The customer sees your reason and can reopen within 7 days.',
      confirmLabel: 'Close it',
      tone: 'danger',
      input: { label: 'Reason', required: true, multiline: true },
    });
    if (!confirmed) return;
    this.busy.set(true);
    this.api.rejectTicket(t.id, value).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.ticket.set(updated);
        this.counts.refresh();
        this.toast.success('Closed without action');
      },
      error: (err) => this.fail(err),
    });
  }

  private fail(err: unknown): void {
    this.busy.set(false);
    this.toast.error(errorMessage(err));
  }

  protected readonly AlarmClock = AlarmClock;
  protected readonly ArrowLeft = ArrowLeft;
}
