import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import {
  LucideDynamicIcon,
  LucideFileSpreadsheet as FileSpreadsheet,
  LucideFileText as FileText,
} from '@lucide/angular';

import { errorMessage } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { GenieApi } from '../../core/api/genie-api';
import { ToastService } from '../../core/ui/feedback';
import { readBlobError, saveBlob } from '../download';
import { Spinner } from './kit';

/**
 * Monthly earnings statement download (Phase 3A-6): pick a month (the current one is "so far"), get a PDF or CSV.
 * The Genie downloads their own; an admin passes {@code genieId}.
 */
@Component({
  selector: 'pg-statement-download',
  imports: [FormsModule, LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col gap-3 sm:flex-row sm:items-end">
      <div class="sm:w-56">
        <label class="label" [for]="selectId">Month</label>
        <select class="field" [id]="selectId" name="month" [(ngModel)]="month">
          @for (m of months; track m.value) {
            <option [value]="m.value">{{ m.label }}</option>
          }
        </select>
      </div>
      <div class="flex gap-2">
        <button type="button" class="btn-primary min-h-11 flex-1 sm:flex-none" [disabled]="busy()" (click)="download('pdf')">
          @if (busy() === 'pdf') {
            <pg-spinner />
          } @else {
            <svg [lucideIcon]="FileText" [size]="16" aria-hidden="true"></svg>
          }
          PDF
        </button>
        <button type="button" class="btn-ghost min-h-11 flex-1 sm:flex-none" [disabled]="busy()" (click)="download('csv')">
          @if (busy() === 'csv') {
            <pg-spinner />
          } @else {
            <svg [lucideIcon]="FileSpreadsheet" [size]="16" aria-hidden="true"></svg>
          }
          CSV (Excel)
        </button>
      </div>
    </div>
  `,
})
export class StatementDownload {
  /** Set for an admin downloading a Genie's statement; omitted for the Genie's own. */
  readonly genieId = input<string | null>(null);

  private readonly genieApi = inject(GenieApi);
  private readonly adminApi = inject(AdminApi);
  private readonly toast = inject(ToastService);

  private static next = 0;
  protected readonly selectId = `statement-month-${StatementDownload.next++}`;
  protected readonly busy = signal<'csv' | 'pdf' | false>(false);
  protected readonly months = lastMonths(13);
  protected month = this.months[0].value;

  protected download(format: 'csv' | 'pdf'): void {
    const id = this.genieId();
    const request: Observable<Blob> = id
      ? this.adminApi.genieStatement(id, this.month, format)
      : this.genieApi.statement(this.month, format);
    this.busy.set(format);
    request.subscribe({
      next: (blob) => {
        this.busy.set(false);
        saveBlob(blob, `progenie-earnings-${this.month}.${format}`);
      },
      error: async (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(await readBlobError(err)));
      },
    });
  }

  protected readonly FileSpreadsheet = FileSpreadsheet;
  protected readonly FileText = FileText;
}

/** This month ("so far") and the previous ones, newest first, as "2026-10" values. */
export function lastMonths(count: number, today = new Date()): { value: string; label: string }[] {
  const out: { value: string; label: string }[] = [];
  for (let i = 0; i < count; i++) {
    const d = new Date(today.getFullYear(), today.getMonth() - i, 1);
    const value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    const label = d.toLocaleDateString('en-IN', { month: 'long', year: 'numeric' }) + (i === 0 ? ' (so far)' : '');
    out.push({ value, label });
  }
  return out;
}
