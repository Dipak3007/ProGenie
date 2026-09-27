import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LucideCalendarX as CalendarX, LucideDynamicIcon, LucideTrash2 as Trash } from '@lucide/angular';

import { API, errorMessage } from '../../core/api/api';
import { GenieApi } from '../../core/api/genie-api';
import { AvailabilityWindow, TimeOff } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { localInputToIso } from '../../shared/format';
import { EmptyState, PageHead, Spinner } from '../../shared/ui/kit';
import { AvailabilityEditor } from './availability-editor';
import { GenieStore } from './genie-store';

/** Today + n days as a datetime-local value in the browser's zone. */
function localInput(daysAhead: number, hour: number): string {
  const d = new Date();
  d.setDate(d.getDate() + daysAhead);
  d.setHours(hour, 0, 0, 0);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

@Component({
  selector: 'pg-availability-page',
  imports: [DatePipe, FormsModule, LucideDynamicIcon, AvailabilityEditor, EmptyState, PageHead, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Availability" subtitle="Your weekly hours and days off. Customers only see free slots inside them." />

    <section class="mt-6" aria-labelledby="week-h">
      <h2 id="week-h" class="text-lg font-bold">Weekly hours</h2>
      @if (store.profile(); as p) {
        <pg-availability-editor class="mt-3" [windows]="p.availability" (saved)="onSaved($event)" />
      }
    </section>

    <section class="mt-10" aria-labelledby="off-h">
      <h2 id="off-h" class="text-lg font-bold">Days off and holidays</h2>
      <p class="mt-1 text-sm text-muted">Block time for festivals, family or training. Existing bookings are not cancelled automatically.</p>

      <form class="card mt-4 grid gap-4 p-5 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_1.2fr_auto] lg:items-end" (ngSubmit)="add()">
        <div>
          <label class="label" for="off-from">From</label>
          <input id="off-from" name="from" type="datetime-local" class="field" step="900" [(ngModel)]="from" required />
        </div>
        <div>
          <label class="label" for="off-to">Until</label>
          <input id="off-to" name="to" type="datetime-local" class="field" step="900" [(ngModel)]="to" required />
        </div>
        <div class="sm:col-span-2 lg:col-span-1">
          <label class="label" for="off-reason">Reason (optional)</label>
          <input id="off-reason" name="reason" class="field" maxlength="200" [(ngModel)]="reason" placeholder="e.g. Diwali" />
        </div>
        <button type="submit" class="btn-primary sm:col-span-2 lg:col-span-1" [disabled]="saving() || !from || !to">
          @if (saving()) {
            <pg-spinner />
          }
          Add
        </button>
      </form>

      <ul class="mt-4 space-y-2">
        @for (t of list.value() ?? []; track t.id) {
          <li class="card flex items-center gap-4 p-4">
            <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-amber-50 text-amber-700">
              <svg [lucideIcon]="CalendarX" [size]="18" aria-hidden="true"></svg>
            </span>
            <div class="min-w-0 flex-1">
              <p class="font-semibold">{{ t.startsAt | date: 'EEE d MMM, h:mm a' }} → {{ t.endsAt | date: 'EEE d MMM, h:mm a' }}</p>
              @if (t.reason) {
                <p class="text-sm text-muted">{{ t.reason }}</p>
              }
            </div>
            <button type="button" class="grid size-10 place-items-center rounded-full text-muted hover:bg-rose-50 hover:text-rose-700" aria-label="Remove time off" (click)="remove(t)">
              <svg [lucideIcon]="Trash" [size]="16" aria-hidden="true"></svg>
            </button>
          </li>
        } @empty {
          @if (!list.isLoading()) {
            <pg-empty [icon]="CalendarX" title="No time off planned" message="Add a day off here and customers won't be able to book it." />
          }
        }
      </ul>
    </section>
  `,
})
export default class AvailabilityPage {
  protected readonly store = inject(GenieStore);
  private readonly api = inject(GenieApi);
  private readonly toast = inject(ToastService);

  protected readonly list = httpResource<TimeOff[]>(() => `${API}/genie/time-off`);
  protected from = localInput(1, 9);
  protected to = localInput(1, 20);
  protected reason = '';
  protected readonly saving = signal(false);

  protected onSaved(windows: AvailabilityWindow[]): void {
    const p = this.store.profile();
    if (p) this.store.set({ ...p, availability: windows });
  }

  protected add(): void {
    if (new Date(this.to) <= new Date(this.from)) {
      this.toast.error('"Until" must be after "From".');
      return;
    }
    this.saving.set(true);
    this.api.addTimeOff({ startsAt: localInputToIso(this.from), endsAt: localInputToIso(this.to), reason: this.reason.trim() || null }).subscribe({
      next: () => {
        this.saving.set(false);
        this.reason = '';
        this.toast.success('Time off added');
        this.list.reload();
      },
      error: (err) => {
        this.saving.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected remove(t: TimeOff): void {
    this.api.deleteTimeOff(t.id).subscribe({
      next: () => {
        this.toast.success('Time off removed');
        this.list.reload();
      },
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }

  protected readonly CalendarX = CalendarX;
  protected readonly Trash = Trash;
}
