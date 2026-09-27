import { ChangeDetectionStrategy, Component, computed, input, model } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { Payment, RefundLiability, RefundRequest } from '../../core/api/models';

/** What can still be refunded on a payment. */
export function refundable(p: Payment): number {
  return Math.max(0, Math.round((p.amount - (p.refundedAmount ?? 0)) * 100) / 100);
}

/** A refund request is complete enough to send. */
export function refundValid(r: RefundRequest, p: Payment): boolean {
  const max = refundable(p);
  if (!r.amount || r.amount <= 0 || r.amount > max) return false;
  if (r.liability === 'SPLIT' && (r.genieShare == null || r.genieShare < 0 || r.genieShare > r.amount)) return false;
  if ((r.method ?? defaultMethod(p)) === 'MANUAL' && !r.manualReference?.trim()) return false;
  return true;
}

export function defaultMethod(p: Payment): 'GATEWAY' | 'MANUAL' {
  return p.method === 'ONLINE' ? 'GATEWAY' : 'MANUAL';
}

export function newRefund(p: Payment): RefundRequest {
  return { amount: refundable(p), liability: 'GENIE', genieShare: null, reason: '', method: defaultMethod(p), manualReference: '' };
}

/**
 * Amount, who carries the cost, and how the money goes back. Online payments go back through the gateway by
 * default; cash (or anything the admin sends by UPI) is a manual refund with the transfer reference.
 */
@Component({
  selector: 'pg-refund-fields',
  imports: [CurrencyPipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let r = value();
    <div class="grid gap-4 sm:grid-cols-2">
      <div>
        <label class="label" [for]="uid + '-amount'">Amount (up to {{ max() | currency: 'INR' }})</label>
        <input
          [id]="uid + '-amount'"
          type="number"
          min="1"
          step="1"
          [max]="max()"
          class="field"
          [ngModel]="r.amount"
          (ngModelChange)="patch({ amount: $event === '' ? null : +$event })"
          [ngModelOptions]="{ standalone: true }"
        />
      </div>
      <div>
        <label class="label" [for]="uid + '-method'">How it goes back</label>
        <select [id]="uid + '-method'" class="field" [ngModel]="r.method" (ngModelChange)="patch({ method: $event })" [ngModelOptions]="{ standalone: true }">
          @if (payment().method === 'ONLINE') {
            <option value="GATEWAY">To the original payment method</option>
          }
          <option value="MANUAL">I sent it by UPI / bank (manual)</option>
        </select>
      </div>
      @if (r.method === 'MANUAL') {
        <div class="sm:col-span-2">
          <label class="label" [for]="uid + '-ref'">UPI / bank reference</label>
          <input [id]="uid + '-ref'" class="field" maxlength="120" [ngModel]="r.manualReference" (ngModelChange)="patch({ manualReference: $event })" [ngModelOptions]="{ standalone: true }" placeholder="e.g. UPI 4521 8876 1234" />
        </div>
      }
      <fieldset class="sm:col-span-2">
        <legend class="label">Who carries the cost?</legend>
        <div class="grid gap-2 sm:grid-cols-3">
          @for (opt of liabilities; track opt.value) {
            <label class="flex cursor-pointer items-start gap-2 rounded-2xl border p-3 text-sm" [class]="r.liability === opt.value ? 'border-brand bg-brand-mist/50' : 'border-line'">
              <input type="radio" class="mt-0.5 accent-brand" [name]="uid + '-liability'" [checked]="r.liability === opt.value" (change)="patch({ liability: opt.value })" />
              <span><span class="block font-semibold">{{ opt.label }}</span><span class="text-xs text-muted">{{ opt.hint }}</span></span>
            </label>
          }
        </div>
      </fieldset>
      @if (r.liability === 'SPLIT') {
        <div>
          <label class="label" [for]="uid + '-share'">Genie's part</label>
          <input
            [id]="uid + '-share'"
            type="number"
            min="0"
            step="1"
            [max]="r.amount ?? 0"
            class="field"
            [ngModel]="r.genieShare"
            (ngModelChange)="patch({ genieShare: $event === '' ? null : +$event })"
            [ngModelOptions]="{ standalone: true }"
          />
          <p class="mt-1 text-xs text-muted">ProGenie pays the rest ({{ platformPart() | currency: 'INR' }}).</p>
        </div>
      }
      @if (showReason()) {
        <div class="sm:col-span-2">
          <label class="label" [for]="uid + '-reason'">Reason (on the credit note)</label>
          <input [id]="uid + '-reason'" class="field" maxlength="300" [ngModel]="r.reason" (ngModelChange)="patch({ reason: $event })" [ngModelOptions]="{ standalone: true }" placeholder="e.g. Genie arrived 2 hours late" />
        </div>
      }
    </div>
  `,
})
export class RefundFields {
  private static seq = 0;
  protected readonly uid = `rf-${++RefundFields.seq}`;

  readonly payment = input.required<Payment>();
  readonly value = model.required<RefundRequest>();
  /** Ticket resolutions fill the reason from the ticket. */
  readonly showReason = input(true);

  protected readonly liabilities: { value: RefundLiability; label: string; hint: string }[] = [
    { value: 'GENIE', label: 'The Genie', hint: 'Poor work, no-show' },
    { value: 'PLATFORM', label: 'ProGenie', hint: 'Goodwill gesture' },
    { value: 'SPLIT', label: 'Split', hint: 'Enter the Genie’s part' },
  ];

  protected readonly max = computed(() => refundable(this.payment()));
  protected readonly platformPart = computed(() => Math.max(0, (this.value().amount ?? 0) - (this.value().genieShare ?? 0)));

  protected patch(change: Partial<RefundRequest>): void {
    this.value.update((v) => ({ ...v, ...change }));
  }
}
