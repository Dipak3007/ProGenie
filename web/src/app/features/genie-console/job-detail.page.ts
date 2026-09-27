import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  LucideArrowLeft as ArrowLeft,
  LucideDynamicIcon,
  LucideHourglass as Hourglass,
  LucideKeyRound as KeyRound,
  LucideLifeBuoy as LifeBuoy,
  LucideMapPin as MapPin,
  LucideNavigation as Navigation,
  LucidePhone as Phone,
} from '@lucide/angular';

import { API, errorCode, errorMessage } from '../../core/api/api';
import { GenieApi } from '../../core/api/genie-api';
import { BookingDetail } from '../../core/api/models';
import { ReportDialog } from '../support/report-dialog';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { inr, mapsLink, telLink } from '../../shared/format';
import { Avatar } from '../../shared/ui/avatar';
import { PriceSummary, Timeline } from '../../shared/ui/booking-parts';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, Skeleton, Spinner } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';
import { GenieStore } from './genie-store';

@Component({
  selector: 'pg-job-detail-page',
  imports: [RouterLink, CurrencyPipe, DatePipe, FormsModule, LucideDynamicIcon, Avatar, Dialog, EmptyState, PriceSummary, ReportDialog, Skeleton, Spinner, StatusBadge, Timeline],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/genie/jobs" class="inline-flex items-center gap-1 text-sm font-semibold text-muted hover:text-brand">
      <svg [lucideIcon]="ArrowLeft" [size]="16" aria-hidden="true"></svg> All jobs
    </a>

    @if (detail.value(); as b) {
      <div class="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2">
        <h1 class="text-2xl font-bold sm:text-3xl">{{ b.serviceName }}</h1>
        <pg-status [status]="b.status" [label]="b.status === 'REQUESTED' ? 'Needs your reply' : null" />
      </div>
      <p class="mt-1 text-sm text-muted">{{ b.bookingRef }} · {{ b.slotStart | date: 'EEEE d MMM, h:mm a' }} – {{ b.slotEnd | date: 'h:mm a' }}</p>

      <div class="mt-6 grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div class="min-w-0 space-y-6">
          <!-- Primary action for the current state -->
          @if (can('ACCEPT')) {
            <section class="card overflow-hidden">
              <div class="flex gap-4 bg-amber-50 p-5">
                <svg [lucideIcon]="Hourglass" [size]="24" class="shrink-0 text-amber-700" aria-hidden="true"></svg>
                <div>
                  <p class="font-semibold">New request, please reply{{ b.expiresAt ? ' by ' + (b.expiresAt | date: 'h:mm a') : '' }}</p>
                  <p class="mt-1 text-sm text-ink/75">You'll earn {{ b.geniePayout | currency: 'INR' }} for this job (paid {{ b.paymentMethod === 'CASH' ? 'in cash by the customer' : 'online, into your wallet' }}).</p>
                </div>
              </div>
              <div class="flex flex-col gap-2 p-5 sm:flex-row">
                <button type="button" class="btn-primary min-h-12 flex-1" [disabled]="busy()" (click)="accept()">Accept job</button>
                <button type="button" class="btn-ghost min-h-12 flex-1" [disabled]="busy()" (click)="decline()">Decline</button>
              </div>
            </section>
          }

          @if (can('START')) {
            <section class="card p-5" aria-labelledby="start-h">
              <h2 id="start-h" class="flex items-center gap-2 text-lg font-bold">
                <svg [lucideIcon]="KeyRound" [size]="20" class="text-brand" aria-hidden="true"></svg> Start the job
              </h2>
              <p class="mt-1 text-sm text-muted">At the door, ask {{ b.customer.name.split(' ')[0] }} for the 4-digit code shown in their app.</p>
              <form class="mt-4 flex flex-col gap-3 sm:flex-row" (ngSubmit)="start()">
                <label class="sr-only" for="otp">Start code</label>
                <input
                  id="otp"
                  name="otp"
                  class="field max-w-[12rem] text-center font-display text-2xl tracking-[0.5em]"
                  inputmode="numeric"
                  autocomplete="one-time-code"
                  maxlength="4"
                  pattern="[0-9]{4}"
                  placeholder="••••"
                  [(ngModel)]="code"
                />
                <button type="submit" class="btn-primary min-h-12" [disabled]="busy() || code.length !== 4">Start job</button>
              </form>
              @if (codeError()) {
                <p class="mt-2 text-sm text-rose-700" role="alert">{{ codeError() }}</p>
              }
            </section>
          } @else if (b.status === 'ACCEPTED') {
            <section class="card p-5">
              <p class="font-semibold">Confirmed for {{ b.slotStart | date: 'EEE d MMM, h:mm a' }}</p>
              <p class="mt-1 text-sm text-muted">You can start up to 30 minutes before the slot with the customer's code.</p>
            </section>
          }

          @if (can('COMPLETE')) {
            <section class="card p-5" aria-labelledby="done-h">
              <h2 id="done-h" class="text-lg font-bold">Finish the job</h2>
              <p class="mt-1 text-sm text-muted">Started {{ b.startedAt | date: 'h:mm a' }}. Add parts or materials if you used any.</p>
              <button type="button" class="btn-gold mt-4 min-h-12" (click)="completeOpen.set(true)">Mark as completed</button>
            </section>
          }

          <!-- Customer & address -->
          <section class="card grid gap-5 p-5 sm:grid-cols-2">
            <div class="flex items-center gap-3 sm:col-span-2">
              <pg-avatar [name]="b.customer.name" [size]="48" />
              <div class="min-w-0 flex-1">
                <p class="font-semibold">{{ b.customer.name }}</p>
                <p class="text-sm text-muted">Customer</p>
              </div>
              @if (tel(); as t) {
                <a [href]="t" class="btn-ghost min-h-10 px-4"><svg [lucideIcon]="Phone" [size]="16" aria-hidden="true"></svg> Call</a>
              }
            </div>
            <div class="sm:col-span-2">
              <p class="text-xs font-semibold uppercase tracking-wider text-muted">Address</p>
              @if (b.address; as a) {
                @if (a.line1) {
                  <p class="mt-1 font-semibold">{{ a.line1 }}{{ a.line2 ? ', ' + a.line2 : '' }}</p>
                  <p class="text-sm text-ink/75">{{ a.landmark ? a.landmark + ' · ' : '' }}{{ a.area ? a.area + ', ' : '' }}{{ a.city }} {{ a.pincode }}</p>
                  <a [href]="directions(b)" target="_blank" rel="noopener" class="btn-ghost mt-3 min-h-10 px-4">
                    <svg [lucideIcon]="Navigation" [size]="16" aria-hidden="true"></svg> Directions
                  </a>
                } @else {
                  <p class="mt-1 flex items-center gap-1 font-semibold"><svg [lucideIcon]="MapPin" [size]="16" aria-hidden="true"></svg> {{ a.area ?? 'Nearby' }}, {{ a.city }}</p>
                  <p class="text-sm text-muted">The full address and phone number appear once you accept.</p>
                }
              }
              <p class="mt-2 text-xs text-muted">{{ b.distanceKm }} km from your base</p>
            </div>
            @if (b.notes) {
              <div class="sm:col-span-2">
                <p class="text-xs font-semibold uppercase tracking-wider text-muted">Customer's note</p>
                <p class="mt-1 rounded-xl bg-surface px-4 py-3 text-sm">{{ b.notes }}</p>
              </div>
            }
          </section>

          <section class="card p-5" aria-labelledby="hist-h">
            <h2 id="hist-h" class="text-lg font-bold">Timeline</h2>
            <pg-timeline class="mt-4" [items]="b.history" />
          </section>
        </div>

        <aside class="space-y-6 xl:sticky xl:top-[calc(var(--header-h)+2.5rem)] xl:self-start">
          <section class="card p-5">
            <div class="flex items-center justify-between gap-3">
              <h2 class="text-lg font-bold">Earnings</h2>
              <pg-status [status]="b.paymentStatus" [label]="(b.paymentMethod === 'CASH' ? 'Cash · ' : 'Online · ') + (b.paymentStatus === 'PAID' ? 'paid' : 'unpaid')" [tone]="b.paymentStatus === 'PAID' ? 'success' : 'neutral'" />
            </div>
            <pg-price-summary class="mt-4" [booking]="b" audience="GENIE" />
            @if (b.paymentMethod === 'CASH' && b.status !== 'COMPLETED') {
              <p class="mt-3 rounded-xl bg-surface px-3 py-2 text-xs text-muted">Collect {{ b.totalAmount | currency: 'INR' }} in cash (plus any extras). The 5% commission is settled from your wallet.</p>
            }
          </section>

          @if (can('CANCEL')) {
            <section class="card p-5">
              <h2 class="text-lg font-bold">Can't make it?</h2>
              <p class="mt-1 text-sm text-muted">Cancelling hurts the customer. Three cancellations in 30 days flags your account for review.</p>
              <button type="button" class="btn mt-3 w-full border border-rose-200 bg-white text-rose-700 hover:bg-rose-50" [disabled]="busy()" (click)="cancel()">Cancel job</button>
            </section>
          }
          @if (canReport(b)) {
            <section class="card p-5">
              <h2 class="text-lg font-bold">Problem with this job?</h2>
              <p class="mt-1 text-sm text-muted">Unsafe situation, abuse or a payment dispute: tell our team.</p>
              <button type="button" class="btn-ghost mt-3 w-full" (click)="reportOpen.set(true)">
                <svg [lucideIcon]="LifeBuoy" [size]="18" aria-hidden="true"></svg> Report a problem
              </button>
            </section>
          }
        </aside>
      </div>
      <pg-report-dialog [open]="reportOpen()" [bookingId]="b.id" [bookingRef]="b.bookingRef" role="GENIE" (closed)="reportOpen.set(false)" />

      <pg-dialog [open]="completeOpen()" heading="Complete the job" size="sm" (closed)="completeOpen.set(false)">
        <form class="space-y-4" (ngSubmit)="complete(b)">
          <div>
            <label class="label" for="extra">Parts / materials (₹, optional)</label>
            <input id="extra" name="extra" type="number" min="0" max="50000" class="field" [(ngModel)]="extraAmount" placeholder="0" />
          </div>
          @if (extraAmount && +extraAmount > 0) {
            <div>
              <label class="label" for="extraNote">What did you use?</label>
              <input id="extraNote" name="extraNote" class="field" maxlength="200" [(ngModel)]="extraNote" placeholder="e.g. 1 capacitor, 2 m wire" required />
            </div>
          }
          <div class="rounded-2xl bg-surface p-4">
            <p class="text-sm text-muted">Customer pays</p>
            <p class="font-display text-3xl font-bold">{{ b.totalAmount + (+extraAmount || 0) | currency: 'INR' }}</p>
          </div>
          @if (b.paymentMethod === 'CASH') {
            <label class="flex items-start gap-3 rounded-2xl border border-line p-4 text-sm">
              <input type="checkbox" name="cash" class="mt-0.5 size-5 rounded accent-brand" [(ngModel)]="cashCollected" />
              <span><span class="font-semibold">I collected the cash.</span> If not, the customer will be asked to pay online.</span>
            </label>
          }
          <div class="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" class="btn-ghost" (click)="completeOpen.set(false)">Back</button>
            <button type="submit" class="btn-gold" [disabled]="busy() || (+extraAmount > 0 && !extraNote.trim())">
              @if (busy()) {
                <pg-spinner />
              }
              Complete job
            </button>
          </div>
        </form>
      </pg-dialog>
    } @else if (detail.isLoading()) {
      <pg-skeleton class="mt-6 block" [rows]="3" />
    } @else if (detail.error()) {
      <pg-empty class="mt-6 block" title="Job not found" message="It may belong to another Genie or the link is wrong.">
        <a routerLink="/genie/jobs" class="btn-ghost">Back to jobs</a>
      </pg-empty>
    }
  `,
})
export default class JobDetailPage {
  readonly id = input.required<string>();

  private readonly api = inject(GenieApi);
  private readonly store = inject(GenieStore);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly detail = httpResource<BookingDetail>(() => `${API}/genie/bookings/${this.id()}`);
  protected readonly reportOpen = signal(false);

  /** Accepted, and the slot ended less than 7 days ago. */
  protected canReport(b: BookingDetail): boolean {
    return !!b.acceptedAt && Date.now() <= new Date(b.slotEnd).getTime() + 7 * 24 * 3600 * 1000;
  }
  protected readonly busy = signal(false);
  protected readonly codeError = signal<string | null>(null);
  protected readonly completeOpen = signal(false);
  protected code = '';
  protected extraAmount: number | string = '';
  protected extraNote = '';
  protected cashCollected = true;

  protected readonly tel = computed(() => telLink(this.detail.value()?.customer.phone));

  protected can(action: string): boolean {
    return this.detail.value()?.allowedActions.includes(action as never) ?? false;
  }

  protected directions(b: BookingDetail): string {
    const a = b.address;
    return mapsLink([a?.line1, a?.line2, a?.landmark, a?.area, a?.city, a?.pincode]);
  }

  private done(b: BookingDetail, message: string): void {
    this.busy.set(false);
    this.detail.set(b);
    this.toast.success(message);
    this.store.refreshRequests();
  }

  private failed(err: unknown): void {
    this.busy.set(false);
    this.toast.error(errorMessage(err));
    if (['INVALID_STATE', 'REQUEST_EXPIRED'].includes(errorCode(err) ?? '')) this.detail.reload();
  }

  protected accept(): void {
    this.busy.set(true);
    this.api.accept(this.id()).subscribe({ next: (b) => this.done(b, 'Job accepted. The customer has been notified.'), error: (e) => this.failed(e) });
  }

  protected async decline(): Promise<void> {
    const { confirmed, value } = await this.confirm.ask({
      title: 'Decline this request?',
      confirmLabel: 'Decline',
      tone: 'danger',
      input: { label: 'Reason for the customer', placeholder: 'e.g. Fully booked at that time', required: true },
    });
    if (!confirmed) return;
    this.busy.set(true);
    this.api.decline(this.id(), value).subscribe({ next: (b) => this.done(b, 'Request declined'), error: (e) => this.failed(e) });
  }

  protected start(): void {
    this.busy.set(true);
    this.codeError.set(null);
    this.api.start(this.id(), this.code).subscribe({
      next: (b) => {
        this.code = '';
        this.done(b, 'Job started. Good luck!');
      },
      error: (err) => {
        this.busy.set(false);
        const code = errorCode(err);
        this.codeError.set(
          code === 'WRONG_START_CODE'
            ? 'That code is not correct. Check it with the customer.'
            : code === 'START_CODE_LOCKED'
              ? 'Too many wrong tries. Ask the customer to generate a new code in their app.'
              : errorMessage(err),
        );
      },
    });
  }

  protected complete(b: BookingDetail): void {
    const extra = Number(this.extraAmount) || 0;
    this.busy.set(true);
    this.api
      .complete(this.id(), {
        extraAmount: extra > 0 ? extra : null,
        extraNote: extra > 0 ? this.extraNote.trim() : null,
        cashCollected: b.paymentMethod === 'CASH' ? this.cashCollected : false,
      })
      .subscribe({
        next: (updated) => {
          this.completeOpen.set(false);
          this.done(updated, `Job completed. ${inr(updated.geniePayout)} earned.`);
        },
        error: (e) => this.failed(e),
      });
  }

  protected async cancel(): Promise<void> {
    const { confirmed, value } = await this.confirm.ask({
      title: 'Cancel this job?',
      message: 'The customer will be notified and can book another Genie. This counts towards your cancellation limit.',
      confirmLabel: 'Cancel job',
      cancelLabel: 'Keep the job',
      tone: 'danger',
      input: { label: 'Reason', placeholder: 'e.g. Family emergency', required: true },
    });
    if (!confirmed) return;
    this.busy.set(true);
    this.api.cancel(this.id(), value).subscribe({ next: (b) => this.done(b, 'Job cancelled'), error: (e) => this.failed(e) });
  }

  protected readonly ArrowLeft = ArrowLeft;
  protected readonly Hourglass = Hourglass;
  protected readonly KeyRound = KeyRound;
  protected readonly LifeBuoy = LifeBuoy;
  protected readonly MapPin = MapPin;
  protected readonly Navigation = Navigation;
  protected readonly Phone = Phone;
}
