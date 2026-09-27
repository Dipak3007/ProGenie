import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import {
  LucideCalendarX as CalendarX,
  LucideDynamicIcon,
  LucideIconInput,
  LucideMoon as Moon,
  LucideSun as Sun,
  LucideSunrise as Sunrise,
} from '@lucide/angular';
import { DayPipe } from './day.pipe';

import { ApiClient, errorMessage } from '../../core/api/api';
import { DayCount, Slot } from '../../core/api/models';
import { IST } from '../format';
import { Spinner } from './kit';

interface SlotGroup {
  label: string;
  icon: LucideIconInput;
  slots: Slot[];
}

/**
 * Two-step time picker backed by the API: a sideways-scrolling strip of days (with free-slot counts),
 * then the free start times of the chosen day grouped into morning / afternoon / evening.
 */
@Component({
  selector: 'pg-slot-picker',
  imports: [DayPipe, DatePipe, LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div class="-mx-4 overflow-x-auto px-4 pb-1 sm:-mx-1 sm:px-1" role="listbox" aria-label="Choose a day">
      <div class="flex min-w-max gap-2">
        @for (d of days(); track d.date) {
          <button
            type="button"
            role="option"
            class="flex w-[4.25rem] flex-col items-center rounded-2xl border px-2 py-2.5 text-center transition disabled:cursor-not-allowed disabled:opacity-40"
            [class]="d.date === day() ? 'border-navy bg-navy text-white shadow-lg shadow-navy/20' : 'border-line bg-white hover:border-brand-soft'"
            [disabled]="d.available === 0"
            [attr.aria-selected]="d.date === day()"
            (click)="pickDay(d.date)"
          >
            <span class="text-[11px] font-semibold uppercase tracking-wide" [class]="d.date === day() ? 'text-brand-soft' : 'text-muted'">{{ d.date | pgDay: 'EEE' }}</span>
            <span class="font-display text-xl font-bold leading-7">{{ d.date | pgDay: 'd' }}</span>
            <span class="text-[11px]" [class]="d.date === day() ? 'text-gold' : 'text-muted'">{{ d.available ? d.available + ' free' : 'Full' }}</span>
          </button>
        } @empty {
          @if (loadingDays()) {
            @for (i of placeholders; track i) {
              <div class="h-[5.25rem] w-[4.25rem] animate-pulse rounded-2xl bg-white"></div>
            }
          }
        }
      </div>
    </div>

    <div class="mt-5" aria-live="polite">
      @if (error()) {
        <p class="rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800">{{ error() }}</p>
      } @else if (loadingSlots()) {
        <p class="flex items-center gap-2 text-sm text-muted"><pg-spinner /> Loading times…</p>
      } @else if (!loadingDays() && days().length > 0 && allFull()) {
        <div class="flex items-center gap-3 rounded-2xl border border-dashed border-line bg-white p-4 text-sm text-muted">
          <svg [lucideIcon]="CalendarX" [size]="20" aria-hidden="true"></svg>
          This Genie has no free time in the next {{ horizon() }} days. Try another Genie.
        </div>
      } @else {
        @for (g of groups(); track g.label) {
          <div class="mb-4">
            <p class="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted">
              <svg [lucideIcon]="g.icon" [size]="14" aria-hidden="true"></svg> {{ g.label }}
            </p>
            <div class="grid grid-cols-3 gap-2 sm:grid-cols-4 xl:grid-cols-5">
              @for (s of g.slots; track s.start) {
                <button
                  type="button"
                  class="min-h-11 rounded-xl border text-sm font-semibold transition"
                  [class]="s.start === selected() ? 'border-brand bg-brand text-white' : 'border-line bg-white hover:border-brand hover:text-brand'"
                  [attr.aria-pressed]="s.start === selected()"
                  (click)="picked.emit(s)"
                >
                  {{ s.start | date: 'h:mm a' : ist }}
                </button>
              }
            </div>
          </div>
        }
      }
    </div>
  `,
})
export class SlotPicker {
  readonly genieId = input.required<string>();
  readonly serviceId = input.required<number>();
  /** ISO start of the currently selected slot. */
  readonly selected = input<string | null>(null);
  readonly horizon = input(14);
  readonly picked = output<Slot>();

  private readonly api = inject(ApiClient);

  protected readonly days = signal<DayCount[]>([]);
  protected readonly day = signal<string | null>(null);
  protected readonly slots = signal<Slot[]>([]);
  protected readonly loadingDays = signal(false);
  protected readonly loadingSlots = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly ist = IST;
  protected readonly placeholders = [1, 2, 3, 4, 5, 6, 7];

  protected readonly allFull = computed(() => this.days().every((d) => d.available === 0));

  protected readonly groups = computed<SlotGroup[]>(() => {
    const byPart: SlotGroup[] = [
      { label: 'Morning', icon: Sunrise, slots: [] },
      { label: 'Afternoon', icon: Sun, slots: [] },
      { label: 'Evening', icon: Moon, slots: [] },
    ];
    for (const s of this.slots()) {
      const hour = Number(new Intl.DateTimeFormat('en-GB', { hour: '2-digit', hour12: false, timeZone: 'Asia/Kolkata' }).format(new Date(s.start)));
      byPart[hour < 12 ? 0 : hour < 17 ? 1 : 2].slots.push(s);
    }
    return byPart.filter((g) => g.slots.length > 0);
  });

  constructor() {
    // Reload whenever the Genie or service changes.
    effect(() => {
      const genieId = this.genieId();
      const serviceId = this.serviceId();
      untracked(() => this.loadDays(genieId, serviceId));
    });
  }

  /** Called by the parent after a "slot taken" error. */
  reload(): void {
    this.loadDays(this.genieId(), this.serviceId(), this.day());
  }

  protected pickDay(date: string): void {
    this.day.set(date);
    this.loadSlots(date);
  }

  private loadDays(genieId: string, serviceId: number, keepDay: string | null = null): void {
    this.loadingDays.set(true);
    this.error.set(null);
    this.days.set([]);
    this.slots.set([]);
    this.api.slotDays(genieId, serviceId, this.horizon()).subscribe({
      next: (days) => {
        this.loadingDays.set(false);
        this.days.set(days);
        const keep = keepDay && days.find((d) => d.date === keepDay && d.available > 0);
        const first = keep ?? days.find((d) => d.available > 0);
        if (first) {
          this.pickDay(first.date);
        } else {
          this.day.set(null);
        }
      },
      error: (err) => {
        this.loadingDays.set(false);
        this.error.set(errorMessage(err, 'Could not load available days.'));
      },
    });
  }

  private loadSlots(date: string): void {
    this.loadingSlots.set(true);
    this.error.set(null);
    this.api.slots(this.genieId(), this.serviceId(), date).subscribe({
      next: (res) => {
        if (this.day() !== date) return; // a newer day was picked meanwhile
        this.loadingSlots.set(false);
        this.slots.set(res.slots);
      },
      error: (err) => {
        this.loadingSlots.set(false);
        this.error.set(errorMessage(err, 'Could not load times.'));
      },
    });
  }

  protected readonly CalendarX = CalendarX;
}
