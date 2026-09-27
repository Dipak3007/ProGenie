import { ChangeDetectionStrategy, Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  LucideArrowLeft as ArrowLeft,
  LucideCalendarDays as CalendarDays,
  LucideClock3 as Clock,
  LucideDynamicIcon,
  LucideHourglass as Hourglass,
  LucideDownload as Download,
  LucideKeyRound as KeyRound,
  LucideLifeBuoy as LifeBuoy,
  LucideMapPin as MapPin,
  LucidePartyPopper as PartyPopper,
  LucidePhone as Phone,
  LucideStar as Star,
  LucideTriangleAlert as TriangleAlert,
} from '@lucide/angular';

import { API, errorCode, errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { BookingDetail, Payment, Receipt, Slot, StartCode } from '../../core/api/models';
import { SupportApi } from '../../core/api/support-api';
import { ReportDialog } from '../support/report-dialog';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { inr, saveBlob, telLink } from '../../shared/format';
import { Avatar } from '../../shared/ui/avatar';
import { PriceSummary, Timeline } from '../../shared/ui/booking-parts';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, Skeleton, Spinner } from '../../shared/ui/kit';
import { SlotPicker } from '../../shared/ui/slot-picker';
import { StatusBadge } from '../../shared/ui/status-badge';
import { PaymentDialog } from './payment-dialog';

const TIP_PRESETS = [0, 20, 50, 100];

@Component({
  selector: 'pg-booking-detail-page',
  imports: [
    RouterLink,
    CurrencyPipe,
    DatePipe,
    DecimalPipe,
    FormsModule,
    LucideDynamicIcon,
    Avatar,
    Dialog,
    EmptyState,
    PaymentDialog,
    PriceSummary,
    ReportDialog,
    Skeleton,
    SlotPicker,
    Spinner,
    StatusBadge,
    Timeline,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/account/bookings" class="inline-flex items-center gap-1 text-sm font-semibold text-muted hover:text-brand">
      <svg [lucideIcon]="ArrowLeft" [size]="16" aria-hidden="true"></svg> All bookings
    </a>

    @if (detail.value(); as b) {
      <div class="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2">
        <h1 class="text-2xl font-bold sm:text-3xl">{{ b.serviceName }}</h1>
        <pg-status [status]="b.status" />
      </div>
      <p class="mt-1 text-sm text-muted">{{ b.categoryName }} · {{ b.bookingRef }} · booked {{ b.createdAt | date: 'd MMM, h:mm a' }}</p>

      <div class="mt-6 grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div class="min-w-0 space-y-6">
          <!-- What happens now -->
          <section class="card overflow-hidden" aria-label="Booking status">
            @switch (b.status) {
              @case ('REQUESTED') {
                <div class="flex gap-4 bg-amber-50 p-5">
                  <svg [lucideIcon]="Hourglass" [size]="24" class="shrink-0 text-amber-700" aria-hidden="true"></svg>
                  <div>
                    <p class="font-semibold">Waiting for {{ b.genie.name }} to accept</p>
                    <p class="mt-1 text-sm text-ink/75">
                      Genies reply within 30 minutes.
                      @if (b.expiresAt) {
                        If there's no answer by {{ b.expiresAt | date: 'h:mm a' }}, the request expires and you can book someone else.
                      }
                    </p>
                  </div>
                </div>
              }
              @case ('ACCEPTED') {
                <div class="flex gap-4 bg-brand-mist/60 p-5">
                  <svg [lucideIcon]="CalendarDays" [size]="24" class="shrink-0 text-brand-600" aria-hidden="true"></svg>
                  <div class="min-w-0 flex-1">
                    <p class="font-semibold">Confirmed. {{ b.genie.name }} will arrive {{ b.slotStart | date: 'EEE d MMM' }} at {{ b.slotStart | date: 'h:mm a' }}.</p>
                    <p class="mt-1 text-sm text-ink/75">When they arrive, give them your 4-digit start code. The job starts only with your code.</p>
                  </div>
                </div>
                @if (can('START_CODE')) {
                  <div class="flex flex-col gap-4 border-t border-line p-5 sm:flex-row sm:items-center">
                    @if (code(); as c) {
                      <div class="flex-1">
                        <p class="text-xs font-semibold uppercase tracking-widest text-muted">Your start code</p>
                        <p class="mt-1 font-display text-5xl font-extrabold tracking-[0.3em] text-brand-600" aria-live="polite">{{ c.code }}</p>
                        <p class="mt-2 text-sm text-muted">Share it only when your Genie is at the door. Generating a new code replaces this one.</p>
                      </div>
                      <button type="button" class="btn-ghost" [disabled]="busy()" (click)="generateCode()">New code</button>
                    } @else {
                      <p class="flex-1 text-sm text-ink/75">For your safety, the code is created only when you need it.</p>
                      <button type="button" class="btn-primary" [disabled]="busy()" (click)="generateCode()">
                        <svg [lucideIcon]="KeyRound" [size]="18" aria-hidden="true"></svg> Show start code
                      </button>
                    }
                  </div>
                }
              }
              @case ('IN_PROGRESS') {
                <div class="flex gap-4 bg-sky-50 p-5">
                  <svg [lucideIcon]="Clock" [size]="24" class="shrink-0 text-sky-700" aria-hidden="true"></svg>
                  <div>
                    <p class="font-semibold">{{ b.genie.name }} is working on it</p>
                    <p class="mt-1 text-sm text-ink/75">Started {{ b.startedAt | date: 'h:mm a' }}. You'll be notified when the job is completed.</p>
                  </div>
                </div>
              }
              @case ('COMPLETED') {
                @if (can('PAY')) {
                  <div class="flex flex-col gap-4 bg-amber-50 p-5 sm:flex-row sm:items-center">
                    <div class="flex flex-1 gap-4">
                      <svg [lucideIcon]="TriangleAlert" [size]="24" class="shrink-0 text-amber-700" aria-hidden="true"></svg>
                      <div>
                        <p class="font-semibold">Job done. Payment of {{ b.totalAmount | currency: 'INR' }} is due</p>
                        <p class="mt-1 text-sm text-ink/75">Pay online now. You can still add a tip before paying.</p>
                      </div>
                    </div>
                    <button type="button" class="btn-gold min-h-12" (click)="payOpen.set(true)">Pay {{ b.totalAmount | currency: 'INR' : 'symbol' : '1.0-0' }}</button>
                  </div>
                } @else {
                  <div class="flex gap-4 bg-emerald-50 p-5">
                    <svg [lucideIcon]="PartyPopper" [size]="24" class="shrink-0 text-emerald-700" aria-hidden="true"></svg>
                    <div>
                      <p class="font-semibold">Job completed{{ b.paymentStatus === 'PAID' ? ' and paid' : '' }}</p>
                      <p class="mt-1 text-sm text-ink/75">
                        @if (b.paymentStatus === 'WAIVED') {
                          This was a free redo. Thanks for giving us another chance!
                        } @else if (b.paymentStatus === 'REFUNDED' || b.paymentStatus === 'PARTIALLY_REFUNDED') {
                          {{ b.paymentStatus === 'REFUNDED' ? 'Fully refunded.' : 'Partly refunded.' }} Your credit note is under Receipts.
                        } @else {
                          {{ b.paymentMethod === 'CASH' ? 'Paid in cash to your Genie.' : 'Paid online.' }} Thanks for using ProGenie!
                        }
                      </p>
                    </div>
                  </div>
                }
              }
              @case ('CANCELLED') {
                @if (can('PAY_FEE')) {
                  <div class="flex flex-col gap-4 bg-rose-50 p-5 sm:flex-row sm:items-center">
                    <div class="flex flex-1 gap-4">
                      <svg [lucideIcon]="TriangleAlert" [size]="24" class="shrink-0 text-rose-700" aria-hidden="true"></svg>
                      <div>
                        <p class="font-semibold">Late-cancellation fee of {{ b.cancellationFee | currency: 'INR' }} is due</p>
                        <p class="mt-1 text-sm text-ink/75">It goes to the Genie who kept the slot for you. You can book again once it's paid.</p>
                      </div>
                    </div>
                    <button type="button" class="btn-gold min-h-12" (click)="payOpen.set(true)">Pay fee</button>
                  </div>
                } @else {
                  <div class="p-5">
                    <p class="font-semibold">This booking was cancelled{{ b.cancelledByRole ? ' by ' + whoCancelled(b.cancelledByRole) : '' }}.</p>
                    @if (b.cancelReason) {
                      <p class="mt-1 text-sm text-muted">Reason: {{ b.cancelReason }}</p>
                    }
                    <a [routerLink]="['/services', b.categorySlug]" class="btn-ghost mt-4">Book another Genie</a>
                  </div>
                }
              }
              @default {
                <div class="p-5">
                  <p class="font-semibold">{{ b.status === 'EXPIRED' ? 'The Genie did not reply in time.' : b.genie.name + ' could not take this job.' }}</p>
                  @if (b.cancelReason) {
                    <p class="mt-1 text-sm text-muted">Reason: {{ b.cancelReason }}</p>
                  }
                  <p class="mt-1 text-sm text-muted">Nothing was charged. Other Genies in {{ b.categoryName }} are ready to help.</p>
                  <a [routerLink]="['/services', b.categorySlug]" class="btn-primary mt-4">Find another Genie</a>
                </div>
              }
            }
          </section>

          <!-- When, where, who -->
          <section class="card grid gap-5 p-5 sm:grid-cols-2">
            <div>
              <p class="text-xs font-semibold uppercase tracking-wider text-muted">When</p>
              <p class="mt-1 font-semibold">{{ b.slotStart | date: 'EEEE, d MMMM' }}</p>
              <p class="text-sm text-ink/75">{{ b.slotStart | date: 'h:mm a' }} – {{ b.slotEnd | date: 'h:mm a' }}</p>
              @if (b.rescheduledCount > 0) {
                <p class="mt-1 text-xs text-muted">Rescheduled {{ b.rescheduledCount }}×</p>
              }
            </div>
            <div>
              <p class="text-xs font-semibold uppercase tracking-wider text-muted">Where</p>
              @if (b.address; as a) {
                <p class="mt-1 font-semibold">{{ a.label }}</p>
                <p class="text-sm text-ink/75">{{ a.line1 }}{{ a.line2 ? ', ' + a.line2 : '' }}</p>
                <p class="text-sm text-ink/75">{{ a.area ? a.area + ', ' : '' }}{{ a.city }} {{ a.pincode }}</p>
              }
            </div>
            <div class="flex items-center gap-3 sm:col-span-2">
              <pg-avatar [name]="b.genie.name" [size]="48" />
              <div class="min-w-0 flex-1">
                <p class="font-semibold">{{ b.genie.name }}</p>
                <p class="text-sm text-muted">
                  Your Genie
                  @if (b.genie.avgRating) {
                    · <svg [lucideIcon]="Star" [size]="13" class="inline fill-gold text-gold" aria-hidden="true"></svg> {{ b.genie.avgRating | number: '1.1-1' }}
                  }
                </p>
              </div>
              @if (tel(); as t) {
                <a [href]="t" class="btn-ghost min-h-10 px-4"><svg [lucideIcon]="Phone" [size]="16" aria-hidden="true"></svg> Call</a>
              }
              <a [routerLink]="['/genies', b.genie.id]" class="hidden text-sm font-semibold text-brand hover:underline sm:inline">Profile</a>
            </div>
            @if (b.notes) {
              <div class="sm:col-span-2">
                <p class="text-xs font-semibold uppercase tracking-wider text-muted">Your note</p>
                <p class="mt-1 text-sm">{{ b.notes }}</p>
              </div>
            }
          </section>

          <!-- Review -->
          @if (can('REVIEW')) {
            <section class="card p-5" aria-labelledby="review-h">
              <h2 id="review-h" class="text-lg font-bold">How did {{ b.genie.name }} do?</h2>
              <p class="mt-1 text-sm text-muted">Your review helps neighbours pick the right Genie.</p>
              <form class="mt-4" (ngSubmit)="submitReview()">
                <div class="flex gap-1" role="radiogroup" aria-label="Rating">
                  @for (n of [1, 2, 3, 4, 5]; track n) {
                    <button
                      type="button"
                      role="radio"
                      class="grid size-11 place-items-center rounded-xl transition hover:bg-gold/10"
                      [attr.aria-checked]="rating() === n"
                      [attr.aria-label]="n + ' star' + (n > 1 ? 's' : '')"
                      (click)="rating.set(n)"
                    >
                      <svg [lucideIcon]="Star" [size]="28" [class]="n <= rating() ? 'fill-gold text-gold' : 'text-line'" aria-hidden="true"></svg>
                    </button>
                  }
                </div>
                <label class="label mt-4" for="review-comment">Comment (optional)</label>
                <textarea id="review-comment" name="comment" class="field min-h-24" maxlength="1000" [(ngModel)]="comment" placeholder="On time? Tidy? Fair price?"></textarea>
                <button type="submit" class="btn-primary mt-4" [disabled]="rating() === 0 || busy()">Post review</button>
              </form>
            </section>
          }

          <section class="card p-5" aria-labelledby="history-h">
            <h2 id="history-h" class="text-lg font-bold">Timeline</h2>
            <pg-timeline class="mt-4" [items]="b.history" />
          </section>
        </div>

        <!-- Money + actions -->
        <aside class="space-y-6 xl:sticky xl:top-[calc(var(--header-h)+2.5rem)] xl:self-start">
          <section class="card p-5">
            <div class="flex items-center justify-between gap-3">
              <h2 class="text-lg font-bold">Price</h2>
              <pg-status [status]="b.paymentStatus" [label]="b.paymentMethod === 'CASH' ? 'Cash · ' + payLabel(b) : 'Online · ' + payLabel(b)" [tone]="b.paymentStatus === 'PAID' ? 'success' : 'neutral'" />
            </div>
            <pg-price-summary class="mt-4" [booking]="b" />

            @if (can('CHANGE_TIP')) {
              <div class="mt-5 border-t border-line pt-4">
                <p class="text-sm font-semibold">Tip for your Genie</p>
                <p class="text-xs text-muted">100% goes to {{ b.genie.name }}.</p>
                <div class="mt-3 grid grid-cols-4 gap-2">
                  @for (t of tipPresets; track t) {
                    <button
                      type="button"
                      class="min-h-10 rounded-xl border text-sm font-semibold"
                      [class]="b.tipAmount === t ? 'border-brand bg-brand text-white' : 'border-line bg-white hover:border-brand'"
                      [disabled]="busy()"
                      (click)="setTip(t)"
                    >
                      {{ t === 0 ? 'None' : '₹' + t }}
                    </button>
                  }
                </div>
              </div>
            }
          </section>

          @if (can('RESCHEDULE') || can('CANCEL')) {
            <section class="card space-y-3 p-5">
              <h2 class="text-lg font-bold">Need to change plans?</h2>
              @if (b.cancellationFeeIfCancelledNow && b.cancellationFeeIfCancelledNow > 0) {
                <p class="rounded-xl bg-rose-50 px-3 py-2 text-sm text-rose-800">
                  It's less than 2 hours to the slot, so cancelling now costs {{ b.cancellationFeeIfCancelledNow | currency: 'INR' }}.
                </p>
              } @else if (b.status === 'ACCEPTED') {
                <p class="text-sm text-muted">Free to cancel or reschedule until 2 hours before the slot.</p>
              } @else {
                <p class="text-sm text-muted">Free to cancel while the Genie hasn't accepted yet.</p>
              }
              @if (can('RESCHEDULE')) {
                <button type="button" class="btn-ghost w-full" (click)="rescheduleOpen.set(true)">Reschedule</button>
              }
              @if (can('CANCEL')) {
                <button type="button" class="btn w-full border border-rose-200 bg-white text-rose-700 hover:bg-rose-50" [disabled]="busy()" (click)="cancel(b)">
                  Cancel booking
                </button>
              }
            </section>
          }

          @if (canReport(b)) {
            <section class="card p-5">
              <h2 class="text-lg font-bold">Something not right?</h2>
              <p class="mt-1 text-sm text-muted">Report it up to 7 days after the job. Our team replies within a day.</p>
              <button type="button" class="btn-ghost mt-3 w-full" (click)="reportOpen.set(true)">
                <svg [lucideIcon]="LifeBuoy" [size]="18" aria-hidden="true"></svg> Report a problem
              </button>
              <a routerLink="/account/tickets" class="mt-2 block text-center text-sm font-semibold text-brand hover:underline">My reports</a>
            </section>
          }

          @if (receipts().length > 0) {
            <section class="card p-5">
              <h2 class="text-lg font-bold">Receipts</h2>
              <ul class="mt-3 divide-y divide-line text-sm">
                @for (r of receipts(); track r.id) {
                  <li class="flex items-center justify-between gap-3 py-2">
                    <span class="min-w-0">
                      <span class="block font-medium wrap-anywhere">{{ r.kind === 'CREDIT_NOTE' ? 'Credit note' : 'Receipt' }} {{ r.number }}</span>
                      <span class="text-xs text-muted">{{ r.issuedAt | date: 'd MMM yyyy' }} · {{ r.amount | currency: 'INR' }}</span>
                    </span>
                    <button type="button" class="btn-ghost min-h-9 shrink-0 px-3" [attr.aria-label]="'Download ' + r.number" (click)="downloadReceipt(r)">
                      <svg [lucideIcon]="Download" [size]="16" aria-hidden="true"></svg> PDF
                    </button>
                  </li>
                }
              </ul>
            </section>
          }

          @if (payments().length > 0) {
            <section class="card p-5">
              <h2 class="text-lg font-bold">Payments</h2>
              <ul class="mt-3 divide-y divide-line text-sm">
                @for (p of payments(); track p.id) {
                  <li class="flex items-center justify-between gap-3 py-2">
                    <span>
                      <span class="block font-medium">{{ p.purpose === 'CANCELLATION_FEE' ? 'Cancellation fee' : 'Booking' }} · {{ p.method }}</span>
                      <span class="text-xs text-muted">{{ p.createdAt | date: 'd MMM, h:mm a' }}{{ p.failureReason ? ' · ' + p.failureReason : '' }}</span>
                    </span>
                    <span class="flex flex-col items-end gap-1">
                      <span class="font-semibold">{{ p.amount | currency: 'INR' }}</span>
                      @if (p.refundedAmount > 0 && p.status !== 'REFUNDED') {
                        <span class="text-xs text-sky-800">{{ p.refundedAmount | currency: 'INR' }} refunded</span>
                      }
                      <pg-status [status]="p.status" />
                    </span>
                  </li>
                }
              </ul>
            </section>
          }
        </aside>
      </div>

      <pg-dialog [open]="rescheduleOpen()" heading="Pick a new time" subheading="The Genie will be asked to confirm the new time." size="lg" (closed)="rescheduleOpen.set(false)">
        @if (rescheduleOpen()) {
          <pg-slot-picker #picker [genieId]="b.genie.id" [serviceId]="b.serviceId" [selected]="newSlot()?.start ?? null" (picked)="newSlot.set($event)" />
          <div class="mt-4 flex flex-col-reverse gap-2 border-t border-line pt-4 sm:flex-row sm:items-center sm:justify-between">
            <p class="text-sm text-muted">
              @if (newSlot(); as s) {
                New time: <span class="font-semibold text-ink">{{ s.start | date: 'EEE d MMM, h:mm a' }}</span>
              } @else {
                Choose a day and time above.
              }
            </p>
            <button type="button" class="btn-primary" [disabled]="!newSlot() || busy()" (click)="reschedule(b)">
              @if (busy()) {
                <pg-spinner />
              }
              Confirm new time
            </button>
          </div>
        }
      </pg-dialog>

      <pg-payment-dialog [open]="payOpen()" [bookingId]="b.id" (closed)="payOpen.set(false)" (paid)="onPaid()" />
      <pg-report-dialog [open]="reportOpen()" [bookingId]="b.id" [bookingRef]="b.bookingRef" role="CUSTOMER" (closed)="reportOpen.set(false)" />
    } @else if (detail.isLoading()) {
      <pg-skeleton class="mt-6 block" [rows]="3" />
    } @else if (detail.error()) {
      <pg-empty class="mt-6 block" title="Booking not found" message="It may belong to another account or the link is wrong.">
        <a routerLink="/account/bookings" class="btn-ghost">Back to bookings</a>
      </pg-empty>
    }
  `,
})
export default class BookingDetailPage {
  readonly id = input.required<string>();

  private readonly api = inject(CustomerApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly detail = httpResource<BookingDetail>(() => `${API}/bookings/${this.id()}`);
  private readonly paymentsRes = httpResource<Payment[]>(() => `${API}/bookings/${this.id()}/payments`);
  protected readonly payments = computed(() => this.paymentsRes.value() ?? []);
  private readonly receiptsRes = httpResource<Receipt[]>(() => `${API}/bookings/${this.id()}/receipts`);
  protected readonly receipts = computed(() => this.receiptsRes.value() ?? []);
  private readonly support = inject(SupportApi);
  protected readonly reportOpen = signal(false);

  protected readonly busy = signal(false);
  protected readonly code = signal<StartCode | null>(null);
  protected readonly rating = signal(0);
  protected comment = '';
  protected readonly rescheduleOpen = signal(false);
  protected readonly newSlot = signal<Slot | null>(null);
  protected readonly payOpen = signal(false);
  protected readonly tipPresets = TIP_PRESETS;
  private readonly picker = viewChild<SlotPicker>('picker');

  protected readonly tel = computed(() => telLink(this.detail.value()?.genie.phone));

  protected can(action: string): boolean {
    return this.detail.value()?.allowedActions.includes(action as never) ?? false;
  }

  protected payLabel(b: BookingDetail): string {
    switch (b.paymentStatus) {
      case 'PAID':
        return 'paid';
      case 'PARTIALLY_REFUNDED':
        return 'partly refunded';
      case 'REFUNDED':
        return 'refunded';
      case 'WAIVED':
        return 'free';
      default:
        return b.status === 'COMPLETED' ? 'due' : 'after the job';
    }
  }

  /** A Genie accepted it, and the slot ended less than 7 days ago. */
  protected canReport(b: BookingDetail): boolean {
    if (!b.acceptedAt) return false;
    return Date.now() <= new Date(b.slotEnd).getTime() + 7 * 24 * 3600 * 1000;
  }

  protected downloadReceipt(r: Receipt): void {
    this.support.receiptPdf(r.id).subscribe({
      next: (blob) => saveBlob(blob, `${r.kind === 'CREDIT_NOTE' ? 'credit-note' : 'receipt'}-${r.number.replace(/\//g, '-')}.pdf`),
      error: (err) => this.toast.error(errorMessage(err, 'Could not download the receipt.')),
    });
  }

  protected whoCancelled(role: string): string {
    return { CUSTOMER: 'you', GENIE: 'the Genie', ADMIN: 'the ProGenie team', SYSTEM: 'the system' }[role] ?? role;
  }

  private apply(b: BookingDetail, message?: string): void {
    this.detail.set(b);
    this.busy.set(false);
    if (message) this.toast.success(message);
  }

  private fail(err: unknown): void {
    this.busy.set(false);
    this.toast.error(errorMessage(err));
    if (errorCode(err) === 'INVALID_STATE' || errorCode(err) === 'SLOT_TAKEN') this.detail.reload();
  }

  protected generateCode(): void {
    this.busy.set(true);
    this.api.startCode(this.id()).subscribe({
      next: (c) => {
        this.busy.set(false);
        this.code.set(c);
      },
      error: (err) => this.fail(err),
    });
  }

  protected setTip(tip: number): void {
    this.busy.set(true);
    this.api.changeTip(this.id(), tip).subscribe({ next: (b) => this.apply(b, tip ? `Tip of ${inr(tip)} added` : 'Tip removed'), error: (err) => this.fail(err) });
  }

  protected async cancel(b: BookingDetail): Promise<void> {
    const fee = b.cancellationFeeIfCancelledNow ?? 0;
    const { confirmed, value } = await this.confirm.ask({
      title: 'Cancel this booking?',
      message: fee > 0 ? `The slot is less than 2 hours away, so a ${inr(fee)} late-cancellation fee will be due to the Genie.` : 'Cancelling now is free.',
      confirmLabel: fee > 0 ? `Cancel and pay ${inr(fee)}` : 'Cancel booking',
      tone: 'danger',
      input: { label: 'Reason (optional)', placeholder: 'e.g. Plans changed' },
    });
    if (!confirmed) return;
    this.busy.set(true);
    this.api.cancel(b.id, value || null).subscribe({
      next: (updated) => {
        this.apply(updated, 'Booking cancelled');
        this.paymentsRes.reload();
      },
      error: (err) => this.fail(err),
    });
  }

  protected reschedule(b: BookingDetail): void {
    const slot = this.newSlot();
    if (!slot) return;
    this.busy.set(true);
    this.api.reschedule(b.id, slot.start).subscribe({
      next: (updated) => {
        this.rescheduleOpen.set(false);
        this.newSlot.set(null);
        this.code.set(null);
        this.apply(updated, 'New time sent to your Genie');
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
        if (errorCode(err) === 'SLOT_TAKEN') {
          this.newSlot.set(null);
          this.picker()?.reload();
        }
      },
    });
  }

  protected submitReview(): void {
    if (this.rating() === 0) return;
    this.busy.set(true);
    this.api.review(this.id(), { rating: this.rating(), comment: this.comment.trim() || null }).subscribe({
      next: () => {
        this.busy.set(false);
        this.toast.success('Thanks! Your review is live.');
        this.detail.reload();
      },
      error: (err) => this.fail(err),
    });
  }

  protected onPaid(): void {
    this.payOpen.set(false);
    this.toast.success('Payment received. Thank you!');
    this.detail.reload();
    this.paymentsRes.reload();
    // The receipt is issued with the payment; give the server a moment to render the PDF.
    setTimeout(() => this.receiptsRes.reload(), 800);
  }

  protected readonly ArrowLeft = ArrowLeft;
  protected readonly CalendarDays = CalendarDays;
  protected readonly Clock = Clock;
  protected readonly Hourglass = Hourglass;
  protected readonly Download = Download;
  protected readonly KeyRound = KeyRound;
  protected readonly LifeBuoy = LifeBuoy;
  protected readonly MapPin = MapPin;
  protected readonly PartyPopper = PartyPopper;
  protected readonly Phone = Phone;
  protected readonly Star = Star;
  protected readonly TriangleAlert = TriangleAlert;
}
