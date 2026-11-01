import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { LucideDynamicIcon, LucidePlus as Plus, LucideTrash2 as Trash } from '@lucide/angular';

import { API, errorMessage } from '../../core/api/api';
import { GenieApi } from '../../core/api/genie-api';
import { BookingDetail, MyProfile } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { Spinner } from '../../shared/ui/kit';

interface EditLine {
  key: number;
  /** null = a custom line */
  serviceId: number | null;
  description: string;
  quantity: number;
  unitPrice: number;
}

const MAX_LINES = 10;
let nextKey = 1;

/**
 * The Genie's quote after an inspection visit (Phase 3.5D): their services and custom lines (commission applies to
 * every line), how long the job takes, a note. Sending again replaces the quote the customer sees.
 */
@Component({
  selector: 'pg-quote-editor',
  imports: [CurrencyPipe, FormsModule, LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <form (submit)="$event.preventDefault(); send()" data-testid="quote-editor">
      <ul class="space-y-3">
        @for (l of lines(); track l.key; let i = $index) {
          <li class="rounded-2xl border border-line p-3" data-testid="quote-line">
            <div class="grid gap-2 sm:grid-cols-[minmax(0,1fr)_5rem_7rem_auto] sm:items-end">
              <div class="min-w-0">
                <label class="label" [for]="'ql-s-' + l.key">Line {{ i + 1 }}</label>
                <select [id]="'ql-s-' + l.key" class="field" [ngModel]="l.serviceId ?? 'custom'" [name]="'ql-s-' + l.key" (ngModelChange)="pick(l.key, $event)">
                  @for (s of services(); track s.serviceId) {
                    <option [ngValue]="s.serviceId">{{ s.name }}</option>
                  }
                  <option ngValue="custom">Custom line…</option>
                </select>
              </div>
              <div>
                <label class="label" [for]="'ql-q-' + l.key">Qty</label>
                <input [id]="'ql-q-' + l.key" type="number" min="1" max="10" class="field" [name]="'ql-q-' + l.key" [ngModel]="l.quantity" (ngModelChange)="patch(l.key, { quantity: +$event })" />
              </div>
              <div>
                <label class="label" [for]="'ql-p-' + l.key">Price each (₹)</label>
                <input [id]="'ql-p-' + l.key" type="number" min="1" class="field" [name]="'ql-p-' + l.key" [ngModel]="l.unitPrice" (ngModelChange)="patch(l.key, { unitPrice: +$event })" />
              </div>
              <button type="button" class="grid size-11 place-items-center rounded-full text-muted hover:bg-rose-50 hover:text-rose-700 disabled:opacity-40" [attr.aria-label]="'Remove line ' + (i + 1)" [disabled]="lines().length === 1" (click)="remove(l.key)">
                <svg [lucideIcon]="Trash" [size]="16" aria-hidden="true"></svg>
              </button>
            </div>
            @if (l.serviceId === null) {
              <label class="label mt-2" [for]="'ql-d-' + l.key">What you'll do</label>
              <input [id]="'ql-d-' + l.key" class="field" maxlength="150" [name]="'ql-d-' + l.key" placeholder="e.g. Replace 20 m of old wiring" [ngModel]="l.description" (ngModelChange)="patch(l.key, { description: $event })" />
            }
            <p class="mt-2 text-right text-sm font-semibold">{{ l.quantity * l.unitPrice | currency: 'INR' }}</p>
          </li>
        }
      </ul>
      @if (lines().length < maxLines) {
        <button type="button" class="btn-ghost mt-3 min-h-10" (click)="add()">
          <svg [lucideIcon]="Plus" [size]="16" aria-hidden="true"></svg> Add a line
        </button>
      }
      <div class="mt-4 grid gap-3 sm:grid-cols-2">
        <div>
          <label class="label" for="q-minutes">How long the job takes (minutes)</label>
          <input id="q-minutes" name="minutes" type="number" min="30" max="480" step="15" class="field" [(ngModel)]="minutes" />
        </div>
        <div class="flex items-end justify-between gap-3 rounded-2xl bg-surface px-4 py-3">
          <span class="text-sm text-muted">Quote total</span>
          <span class="font-display text-xl font-bold" data-testid="quote-total">{{ total() | currency: 'INR' }}</span>
        </div>
      </div>
      <label class="label mt-4" for="q-note">Note for the customer <span class="font-normal text-muted">(optional)</span></label>
      <textarea id="q-note" name="note" class="field min-h-20" maxlength="500" [(ngModel)]="note" placeholder="e.g. Materials included. 6-month warranty on the wiring."></textarea>
      <p class="mt-2 text-xs text-muted">
        The customer pays this minus the {{ booking().serviceAmount | currency: 'INR' : 'symbol' : '1.0-0' }} inspection fee, plus travel. ProGenie's commission applies to every line.
        The quote stays open 7 days; you can change it until they answer.
      </p>
      @if (error()) {
        <p class="mt-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
      }
      <button type="submit" class="btn-primary mt-4 w-full sm:w-auto" [disabled]="busy() || !valid()">
        @if (busy()) {
          <pg-spinner />
        }
        {{ booking().quote ? 'Send updated quote' : 'Send quote' }}
      </button>
    </form>
  `,
})
export class QuoteEditor {
  readonly booking = input.required<BookingDetail>();
  readonly sent = output<BookingDetail>();

  private readonly api = inject(GenieApi);
  private readonly toast = inject(ToastService);
  private readonly profile = httpResource<MyProfile>(() => `${API}/genie/profile`);
  protected readonly services = computed(() => this.profile.value()?.services ?? []);
  protected readonly lines = signal<EditLine[]>([]);
  protected minutes = 60;
  protected note = '';
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly maxLines = MAX_LINES;
  private started = false;

  protected readonly total = computed(() => this.lines().reduce((sum, l) => sum + (l.quantity || 0) * (l.unitPrice || 0), 0));
  protected readonly valid = computed(() =>
    this.lines().length > 0 &&
    this.lines().every((l) => l.quantity >= 1 && l.quantity <= 10 && l.unitPrice >= 1 && (l.serviceId !== null || l.description.trim().length > 0)),
  );

  constructor() {
    // Start from the current quote, else from the services the customer asked to be looked at.
    effect(() => {
      const services = this.services();
      const b = this.booking();
      if (!services.length || this.started) return;
      untracked(() => {
        this.started = true;
        if (b.quote) {
          this.lines.set(b.quote.lines.map((l) => ({ key: nextKey++, serviceId: l.serviceId ?? null, description: l.serviceId ? '' : l.description, quantity: l.quantity, unitPrice: l.unitPrice })));
          this.minutes = b.quote.durationMinutes;
          this.note = b.quote.note ?? '';
          return;
        }
        const asked = b.items.filter((i) => i.serviceId != null && services.some((s) => s.serviceId === i.serviceId));
        const start = asked.length ? asked : [{ serviceId: services[0].serviceId, quantity: 1 }];
        this.lines.set(start.map((i) => this.lineFor(i.serviceId!, i.quantity)));
        this.minutes = Math.min(480, Math.max(30, start.reduce((sum, i) => sum + (services.find((s) => s.serviceId === i.serviceId)?.durationMinutes ?? 0) * i.quantity, 0)));
      });
    });
  }

  private lineFor(serviceId: number, quantity = 1): EditLine {
    const s = this.services().find((x) => x.serviceId === serviceId);
    return { key: nextKey++, serviceId, description: '', quantity, unitPrice: s?.price ?? 0 };
  }

  protected pick(key: number, value: number | 'custom'): void {
    this.lines.update((list) =>
      list.map((l) => (l.key !== key ? l : value === 'custom' ? { ...l, serviceId: null, description: '' } : { ...this.lineFor(value, l.quantity), key })),
    );
  }

  protected patch(key: number, change: Partial<EditLine>): void {
    this.lines.update((list) => list.map((l) => (l.key === key ? { ...l, ...change } : l)));
  }

  protected add(): void {
    if (this.lines().length >= MAX_LINES) return;
    this.lines.update((list) => [...list, { key: nextKey++, serviceId: null, description: '', quantity: 1, unitPrice: 0 }]);
  }

  protected remove(key: number): void {
    this.lines.update((list) => (list.length > 1 ? list.filter((l) => l.key !== key) : list));
  }

  protected send(): void {
    if (!this.valid() || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    const body = {
      lines: this.lines().map((l) => ({
        serviceId: l.serviceId,
        description: l.serviceId === null ? l.description.trim() : null,
        quantity: l.quantity,
        unitPrice: l.unitPrice,
      })),
      durationMinutes: Math.min(480, Math.max(30, Math.round(Number(this.minutes) || 60))),
      note: this.note.trim() || null,
    };
    this.api.sendQuote(this.booking().id, body).subscribe({
      next: (b) => {
        this.busy.set(false);
        this.toast.success(this.booking().quote ? 'Updated quote sent' : 'Quote sent to the customer');
        this.sent.emit(b);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Could not send the quote.'));
      },
    });
  }

  protected readonly Plus = Plus;
  protected readonly Trash = Trash;
}
