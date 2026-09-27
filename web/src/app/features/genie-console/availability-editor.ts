import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import {
  LucideCopy as Copy,
  LucideDynamicIcon,
  LucidePlus as Plus,
  LucideTrash2 as Trash,
} from '@lucide/angular';

import { errorMessage } from '../../core/api/api';
import { GenieApi } from '../../core/api/genie-api';
import { AvailabilityWindow } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { DAY_NAMES, hhmm } from '../../shared/format';
import { Spinner } from '../../shared/ui/kit';

interface Row {
  start: string;
  end: string;
}

const FULL_DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

/** Weekly working hours: each day can have up to three windows on 15-minute boundaries. */
@Component({
  selector: 'pg-availability-editor',
  imports: [LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <ul class="divide-y divide-line rounded-2xl border border-line bg-white">
      @for (day of week(); track $index; let d = $index) {
        <li class="flex flex-col gap-3 p-4 sm:flex-row sm:items-start">
          <label class="flex w-40 shrink-0 items-center gap-3 pt-2">
            <input type="checkbox" class="size-5 rounded accent-brand" [checked]="day.length > 0" (change)="toggleDay(d, $any($event.target).checked)" [disabled]="disabled()" />
            <span class="font-semibold">{{ fullDays[d] }}</span>
          </label>
          <div class="min-w-0 flex-1 space-y-2">
            @for (w of day; track $index; let i = $index) {
              <div class="flex flex-wrap items-center gap-2">
                <input
                  type="time"
                  step="900"
                  class="field w-[9.75rem] px-3 py-2"
                  [value]="w.start"
                  [attr.aria-label]="fullDays[d] + ' start ' + (i + 1)"
                  (change)="setTime(d, i, 'start', $any($event.target).value)"
                  [disabled]="disabled()"
                />
                <span class="text-muted">to</span>
                <input
                  type="time"
                  step="900"
                  class="field w-[9.75rem] px-3 py-2"
                  [value]="w.end"
                  [attr.aria-label]="fullDays[d] + ' end ' + (i + 1)"
                  (change)="setTime(d, i, 'end', $any($event.target).value)"
                  [disabled]="disabled()"
                />
                <button type="button" class="grid size-10 place-items-center rounded-full text-muted hover:bg-rose-50 hover:text-rose-700" [attr.aria-label]="'Remove window ' + (i + 1)" (click)="removeWindow(d, i)" [disabled]="disabled()">
                  <svg [lucideIcon]="Trash" [size]="16" aria-hidden="true"></svg>
                </button>
              </div>
            } @empty {
              <p class="pt-2 text-sm text-muted">Day off</p>
            }
            @if (day.length > 0 && day.length < 3) {
              <button type="button" class="inline-flex items-center gap-1 text-sm font-semibold text-brand hover:underline" (click)="addWindow(d)" [disabled]="disabled()">
                <svg [lucideIcon]="Plus" [size]="14" aria-hidden="true"></svg> Add a break / second shift
              </button>
            }
          </div>
          @if (d === 0 && day.length > 0) {
            <button type="button" class="inline-flex items-center gap-1 self-start rounded-full px-3 py-2 text-xs font-semibold text-muted hover:bg-surface hover:text-brand" (click)="copyMonday()" [disabled]="disabled()">
              <svg [lucideIcon]="Copy" [size]="14" aria-hidden="true"></svg> Copy to Tue–Sat
            </button>
          }
        </li>
      }
    </ul>

    @if (problem()) {
      <p class="mt-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ problem() }}</p>
    }
    <div class="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <p class="text-sm text-muted">{{ summary() }}</p>
      <button type="button" class="btn-primary" [disabled]="saving() || !!problem() || !dirty() || disabled()" (click)="save()">
        @if (saving()) {
          <pg-spinner />
        }
        Save hours
      </button>
    </div>
  `,
})
export class AvailabilityEditor {
  readonly windows = input.required<AvailabilityWindow[]>();
  readonly disabled = input(false);
  readonly saved = output<AvailabilityWindow[]>();

  private readonly api = inject(GenieApi);
  private readonly toast = inject(ToastService);

  protected readonly week = signal<Row[][]>(Array.from({ length: 7 }, () => []));
  protected readonly dirty = signal(false);
  protected readonly saving = signal(false);
  protected readonly fullDays = FULL_DAYS;

  constructor() {
    effect(() => {
      const list = this.windows();
      untracked(() => {
        const week: Row[][] = Array.from({ length: 7 }, () => []);
        for (const w of list) week[w.dayOfWeek - 1]?.push({ start: hhmm(w.startTime), end: hhmm(w.endTime) });
        week.forEach((day) => day.sort((a, b) => a.start.localeCompare(b.start)));
        this.week.set(week);
        this.dirty.set(false);
      });
    });
  }

  /** Client-side version of the API's rules, so problems show before saving. */
  protected readonly problem = computed(() => {
    const week = this.week();
    for (let d = 0; d < 7; d++) {
      const day = [...week[d]].sort((a, b) => a.start.localeCompare(b.start));
      for (let i = 0; i < day.length; i++) {
        const w = day[i];
        if (!w.start || !w.end) return `${FULL_DAYS[d]}: enter both a start and an end time.`;
        if (w.start >= w.end) return `${FULL_DAYS[d]}: ${w.start} must be before ${w.end}.`;
        if (![w.start, w.end].every((t) => Number(t.slice(3, 5)) % 15 === 0)) return `${FULL_DAYS[d]}: use times on the quarter hour (:00, :15, :30, :45).`;
        if (i > 0 && day[i - 1].end > w.start) return `${FULL_DAYS[d]}: two windows overlap.`;
      }
    }
    if (week.every((d) => d.length === 0)) return 'Add working hours for at least one day.';
    return null;
  });

  protected readonly summary = computed(() => {
    const minutes = this.week()
      .flat()
      .reduce((sum, w) => {
        const [sh, sm] = w.start.split(':').map(Number);
        const [eh, em] = w.end.split(':').map(Number);
        return sum + Math.max(0, eh * 60 + em - (sh * 60 + sm));
      }, 0);
    const days = this.week().filter((d) => d.length > 0).length;
    return `${days} working day${days === 1 ? '' : 's'} · ${Math.round(minutes / 60)} hours a week`;
  });

  private update(fn: (week: Row[][]) => void): void {
    const copy = this.week().map((day) => day.map((w) => ({ ...w })));
    fn(copy);
    this.week.set(copy);
    this.dirty.set(true);
  }

  protected toggleDay(d: number, on: boolean): void {
    this.update((week) => (week[d] = on ? [{ start: '09:00', end: '18:00' }] : []));
  }

  protected addWindow(d: number): void {
    this.update((week) => {
      const last = week[d][week[d].length - 1];
      const start = last && last.end < '20:00' ? last.end : '18:00';
      const [h, m] = start.split(':').map(Number);
      const end = `${String(Math.min(h + 2, 23)).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
      week[d].push({ start, end });
    });
  }

  protected removeWindow(d: number, i: number): void {
    this.update((week) => week[d].splice(i, 1));
  }

  protected setTime(d: number, i: number, field: 'start' | 'end', value: string): void {
    this.update((week) => (week[d][i][field] = value));
  }

  protected copyMonday(): void {
    this.update((week) => {
      for (let d = 1; d <= 5; d++) week[d] = week[0].map((w) => ({ ...w }));
    });
  }

  protected save(): void {
    if (this.problem()) return;
    const windows: AvailabilityWindow[] = this.week().flatMap((day, d) => day.map((w) => ({ dayOfWeek: d + 1, startTime: w.start, endTime: w.end })));
    this.saving.set(true);
    this.api.saveAvailability(windows).subscribe({
      next: (res) => {
        this.saving.set(false);
        this.dirty.set(false);
        this.toast.success('Working hours saved');
        this.saved.emit(res);
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected readonly Copy = Copy;
  protected readonly Plus = Plus;
  protected readonly Trash = Trash;
  protected readonly dayNames = DAY_NAMES;
}
