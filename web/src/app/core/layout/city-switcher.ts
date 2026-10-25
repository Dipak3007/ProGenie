import { ChangeDetectionStrategy, Component, ElementRef, inject, input, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  LucideCheck as Check,
  LucideChevronDown as ChevronDown,
  LucideDynamicIcon,
  LucideMapPin as MapPin,
} from '@lucide/angular';

import { errorMessage } from '../api/api';
import { PincodeCheck } from '../api/models';
import { CityStore } from '../city/city-store';
import { Spinner } from '../../shared/ui/kit';

/**
 * City pill in the header: pick a live city, or type a PIN code to see whether ProGenie serves it.
 * The city list is data (GET /cities), so a newly opened city appears here without a release.
 * variant 'inline' is the open panel used inside the mobile menu.
 */
@Component({
  selector: 'pg-city-switcher',
  imports: [FormsModule, NgTemplateOutlet, LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:click)': 'onDocumentClick($event)', '(document:keydown.escape)': 'open.set(false)' },
  template: `
    @if (variant() === 'pill') {
      <div class="relative" data-popover>
        <button
          type="button"
          class="inline-flex min-h-9 items-center gap-1.5 rounded-full bg-white/10 px-3 text-xs font-semibold text-brand-mist hover:bg-white/15 hover:text-white"
          [attr.aria-expanded]="open()"
          aria-haspopup="dialog"
          [attr.aria-label]="'City: ' + (store.city()?.name ?? 'choose') + '. Change city or check a PIN code'"
          (click)="open.set(!open())"
        >
          <svg [lucideIcon]="MapPin" [size]="14" aria-hidden="true"></svg>
          {{ store.city()?.name ?? 'Choose city' }}
          <svg [lucideIcon]="ChevronDown" [size]="14" aria-hidden="true"></svg>
        </button>
        @if (open()) {
          <div
            class="fixed inset-x-3 top-[calc(var(--header-h)+0.5rem)] z-50 rounded-2xl bg-white p-4 text-ink shadow-2xl ring-1 ring-line sm:absolute sm:inset-x-auto sm:left-0 sm:top-11 sm:w-80"
            role="dialog"
            aria-label="Choose your city"
          >
            <ng-container *ngTemplateOutlet="panel" />
          </div>
        }
      </div>
    } @else {
      <div class="rounded-2xl bg-white/5 p-4 text-white"><ng-container *ngTemplateOutlet="panel" /></div>
    }

    <ng-template #panel>
      <p class="text-xs font-semibold uppercase tracking-widest" [class]="variant() === 'pill' ? 'text-muted' : 'text-brand-soft'">Your city</p>
      <ul class="mt-2 space-y-1">
        @for (c of store.cities(); track c.id) {
          <li>
            <button
              type="button"
              class="flex min-h-11 w-full items-center justify-between rounded-xl px-3 text-left text-sm font-medium"
              [class]="variant() === 'pill' ? 'hover:bg-surface' : 'hover:bg-white/10'"
              [attr.aria-pressed]="c.slug === store.slug()"
              (click)="choose(c.slug)"
            >
              <span>{{ c.name }}, {{ c.state }}</span>
              @if (c.slug === store.slug()) {
                <svg [lucideIcon]="Check" [size]="16" class="text-brand" aria-hidden="true"></svg>
              }
            </button>
          </li>
        } @empty {
          <li class="px-3 py-2 text-sm opacity-70">{{ store.loading() ? 'Loading cities…' : 'No city is live yet.' }}</li>
        }
      </ul>

      <form class="mt-4 border-t pt-4" [class]="variant() === 'pill' ? 'border-line' : 'border-white/10'" (ngSubmit)="check()">
        <label class="text-sm font-semibold" [for]="id + '-pin'">Check your PIN code</label>
        <div class="mt-2 flex gap-2">
          <input
            [id]="id + '-pin'"
            name="pincode"
            class="field min-w-0 flex-1 text-ink"
            inputmode="numeric"
            autocomplete="postal-code"
            maxlength="6"
            placeholder="380009"
            [(ngModel)]="pincode"
            (ngModelChange)="result.set(null); error.set(null)"
          />
          <button type="submit" class="btn-primary shrink-0" [disabled]="checking() || !valid()">
            @if (checking()) {
              <pg-spinner [size]="16" />
            }
            Check
          </button>
        </div>
        <p class="mt-2 min-h-5 text-sm" aria-live="polite">
          @if (result(); as r) {
            @if (r.serviceable) {
              <span class="font-medium" [class]="variant() === 'pill' ? 'text-emerald-700' : 'text-emerald-300'">We serve {{ r.pincode }}{{ r.area ? ' · ' + r.area : '' }}, {{ r.cityName }}.</span>
            } @else {
              <span class="font-medium" [class]="variant() === 'pill' ? 'text-amber-700' : 'text-amber-300'">Not in {{ r.pincode }} yet. We're live in {{ store.liveNames() }}.</span>
            }
          } @else if (error()) {
            <span [class]="variant() === 'pill' ? 'text-rose-700' : 'text-rose-300'">{{ error() }}</span>
          }
        </p>
      </form>
    </ng-template>
  `,
})
export class CitySwitcher {
  readonly variant = input<'pill' | 'inline'>('pill');

  protected readonly store = inject(CityStore);
  private readonly el = inject(ElementRef<HTMLElement>);
  private static next = 0;
  protected readonly id = `city-${CitySwitcher.next++}`;

  protected readonly open = signal(false);
  protected readonly checking = signal(false);
  protected readonly result = signal<PincodeCheck | null>(null);
  protected readonly error = signal<string | null>(null);
  protected pincode = '';

  protected readonly MapPin = MapPin;
  protected readonly ChevronDown = ChevronDown;
  protected readonly Check = Check;

  protected valid(): boolean {
    return /^[1-9][0-9]{5}$/.test(this.pincode.trim());
  }

  protected choose(slug: string): void {
    this.store.select(slug);
    this.open.set(false);
  }

  protected check(): void {
    if (!this.valid()) return;
    this.checking.set(true);
    this.error.set(null);
    this.store.checkPincode(this.pincode.trim()).subscribe({
      next: (r) => {
        this.checking.set(false);
        this.result.set(r);
      },
      error: (err) => {
        this.checking.set(false);
        this.error.set(errorMessage(err, 'Could not check that PIN code.'));
      },
    });
  }

  protected onDocumentClick(event: MouseEvent): void {
    const target = event.target as HTMLElement | null;
    if (this.open() && (!target || !this.el.nativeElement.contains(target))) this.open.set(false);
  }
}
