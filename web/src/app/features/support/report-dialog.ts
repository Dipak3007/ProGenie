import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { errorCode, errorMessage } from '../../core/api/api';
import { HelpApi } from '../../core/api/help-api';
import { BookingItem, HelpArticle, Role, TicketCategory } from '../../core/api/models';
import { SupportApi } from '../../core/api/support-api';
import { ToastService } from '../../core/ui/feedback';
import { TICKET_CATEGORY_LABEL } from '../../shared/format';
import { Dialog } from '../../shared/ui/dialog';
import { Spinner } from '../../shared/ui/kit';
import { Markdown } from '../../shared/ui/markdown';

const CUSTOMER_CATEGORIES: TicketCategory[] = ['QUALITY', 'NO_SHOW', 'DAMAGE', 'PAYMENT', 'BEHAVIOUR', 'SAFETY', 'OTHER'];
const GENIE_CATEGORIES: TicketCategory[] = ['PAYMENT', 'BEHAVIOUR', 'SAFETY', 'OTHER'];

/** "Report a problem" on a booking (customer) or a job (Genie). Photos are added on the report afterwards. */
@Component({
  selector: 'pg-report-dialog',
  imports: [FormsModule, RouterLink, Dialog, Markdown, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-dialog [open]="open()" heading="Report a problem" [subheading]="bookingRef()" size="md" (closed)="closed.emit()">
      @if (open()) {
        <form (ngSubmit)="submit()" #f="ngForm">
          <fieldset>
            <legend class="label">What went wrong?</legend>
            <div class="grid gap-2 sm:grid-cols-2">
              @for (c of categories(); track c) {
                <label
                  class="flex cursor-pointer items-center gap-3 rounded-2xl border p-3 text-sm transition"
                  [class]="category() === c ? (c === 'SAFETY' ? 'border-rose-400 bg-rose-50' : 'border-brand bg-brand-mist/50') : 'border-line hover:border-brand-soft'"
                >
                  <input type="radio" name="category" class="accent-brand" [value]="c" [checked]="category() === c" (change)="category.set(c)" />
                  <span class="font-medium">{{ labels[c] }}</span>
                </label>
              }
            </div>
          </fieldset>
          @if (suggestions().length) {
            <div class="mt-4 rounded-2xl bg-brand-mist/40 p-4" data-testid="help-suggestions">
              <p class="text-sm font-semibold">This might answer it</p>
              <ul class="mt-2 space-y-1">
                @for (a of suggestions(); track a.id) {
                  <li>
                    <details class="rounded-xl bg-white px-3 py-2 text-sm">
                      <summary class="cursor-pointer font-medium">{{ a.question }}</summary>
                      <pg-markdown class="mt-2 block" [text]="a.answer" />
                    </details>
                  </li>
                }
              </ul>
              <div class="mt-3 flex flex-wrap items-center gap-3">
                <button type="button" class="btn-ghost min-h-10 bg-white px-4 text-sm" (click)="answered()">This answered my question</button>
                <a routerLink="/help" class="text-sm font-semibold text-brand underline" (click)="closed.emit()">More answers</a>
              </div>
            </div>
          }
          @if (items().length > 1) {
            <fieldset class="mt-4" data-testid="report-items">
              <legend class="label">Which service? <span class="font-normal text-muted">(tick the ones it's about; none = the whole booking)</span></legend>
              <div class="flex flex-wrap gap-2">
                @for (i of items(); track i.id) {
                  <label class="flex cursor-pointer items-center gap-2 rounded-full border px-3 py-2 text-sm" [class]="picked().includes(i.id) ? 'border-brand bg-brand-mist/50' : 'border-line'">
                    <input type="checkbox" class="size-4 accent-brand" [checked]="picked().includes(i.id)" (change)="toggleItem(i.id)" />
                    {{ i.serviceName }}{{ i.quantity > 1 ? ' ×' + i.quantity : '' }}
                  </label>
                }
              </div>
            </fieldset>
          }
          @if (category() === 'SAFETY') {
            <p class="mt-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800">
              Our team is alerted at once and replies within an hour. If you are in danger right now, call 112.
            </p>
          }
          <label class="label mt-4" for="rp-subject">Short summary</label>
          <input id="rp-subject" name="subject" class="field" required maxlength="150" [(ngModel)]="subject" placeholder="e.g. The new switch stopped working" />
          <label class="label mt-4" for="rp-desc">What happened?</label>
          <textarea id="rp-desc" name="description" class="field min-h-28" required maxlength="2000" [(ngModel)]="description" placeholder="When, what you noticed, what you'd like us to do"></textarea>
          <p class="mt-1 text-xs text-muted">You can add up to 5 photos on the next screen. The {{ role() === 'GENIE' ? 'customer' : 'Genie' }} can see and reply to your report.</p>
          @if (error()) {
            <p class="mt-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
          }
          <div class="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" class="btn-ghost" (click)="closed.emit()">Cancel</button>
            <button type="submit" class="btn-primary" [disabled]="!category() || !f.valid || busy()">
              @if (busy()) {
                <pg-spinner />
              }
              Send report
            </button>
          </div>
        </form>
      }
    </pg-dialog>
  `,
})
export class ReportDialog {
  readonly open = input(false);
  readonly bookingId = input.required<string>();
  readonly bookingRef = input<string | null>(null);
  readonly role = input<Role>('CUSTOMER');
  /** The booking's services (Phase 3.5C): with several, the reporter can say which ones the problem is about. */
  readonly items = input<BookingItem[]>([]);
  readonly closed = output<void>();
  protected readonly picked = signal<number[]>([]);

  private readonly api = inject(SupportApi);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);

  protected readonly labels = TICKET_CATEGORY_LABEL;
  protected readonly category = signal<TicketCategory | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected subject = '';
  protected description = '';

  private readonly help = inject(HelpApi);
  /** Answers for the chosen category (Phase 3.5B), shown before the form; never for safety reports. */
  protected readonly suggestions = signal<HelpArticle[]>([]);

  constructor() {
    effect(() => {
      const c = this.category();
      untracked(() => {
        this.suggestions.set([]);
        if (!c || c === 'SAFETY') return;
        this.help.suggestions(c).subscribe({
          next: (list) => {
            if (this.category() === c) this.suggestions.set(list);
          },
          error: () => undefined,
        });
      });
    });
  }

  protected answered(): void {
    this.toast.success('Glad that helped.');
    this.closed.emit();
  }

  protected categories(): TicketCategory[] {
    return this.role() === 'GENIE' ? GENIE_CATEGORIES : CUSTOMER_CATEGORIES;
  }

  protected toggleItem(id: number): void {
    this.picked.update((ids) => (ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]));
  }

  protected submit(): void {
    const category = this.category();
    if (!category) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.raise(this.role(), this.bookingId(), { category, subject: this.subject.trim(), description: this.description.trim(), itemIds: this.picked() }).subscribe({
      next: (t) => {
        this.busy.set(false);
        this.toast.success(`Report ${t.ticketRef} sent. We'll get back to you soon.`);
        this.closed.emit();
        this.router.navigate([this.role() === 'GENIE' ? '/genie/tickets' : '/account/tickets', t.id]);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err));
        if (errorCode(err) === 'TICKET_ALREADY_OPEN') this.toast.info('Find the open report under Reported problems.');
      },
    });
  }
}
