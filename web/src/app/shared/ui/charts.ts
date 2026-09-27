import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

export interface ChartPoint {
  /** Category or ISO date ("2026-09-01"). */
  label: string;
  value: number;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-09-07" → "7 Sep"; other labels unchanged. */
export function shortLabel(label: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(label);
  return m ? `${+m[3]} ${MONTHS[+m[2] - 1]}` : label;
}

/** Rounds the axis maximum up to a clean number (1, 2, 2.5, 5 × 10ⁿ). */
export function niceMax(max: number): number {
  if (max <= 0) return 1;
  const exp = Math.pow(10, Math.floor(Math.log10(max)));
  const f = max / exp;
  const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
  return nice * exp;
}

/**
 * Single-series column chart (e.g. completed jobs per day).
 * Thin 4px-rounded columns on one baseline, hairline gridlines, a tooltip per column on hover and keyboard focus,
 * and a table view so no value depends on hovering. The title names the series, so there is no legend.
 */
@Component({
  selector: 'pg-column-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <figure class="relative" [attr.aria-label]="title()">
      <div class="relative h-48 w-full sm:h-56" #box>
        @if (width() > 0) {
          <svg [attr.width]="width()" [attr.height]="height()" class="block overflow-visible" role="img" [attr.aria-label]="title() + ', ' + points().length + ' values'">
            <!-- gridlines + y ticks -->
            @for (t of ticks(); track t) {
              <line [attr.x1]="padL" [attr.x2]="width()" [attr.y1]="y(t)" [attr.y2]="y(t)" stroke="#e0e4f5" stroke-width="1" />
              <text [attr.x]="padL - 8" [attr.y]="y(t) + 4" text-anchor="end" class="fill-muted text-[11px] tabular-nums">{{ format()(t) }}</text>
            }
            <!-- columns -->
            @for (p of points(); track p.label; let i = $index) {
              <g
                tabindex="0"
                class="cursor-default outline-none"
                [attr.aria-label]="shortLabel(p.label) + ': ' + format()(p.value)"
                (pointerenter)="active.set(i)"
                (pointerleave)="active.set(null)"
                (focus)="active.set(i)"
                (blur)="active.set(null)"
              >
                <!-- hit target: the whole band, taller than the mark -->
                <rect [attr.x]="bandX(i)" [attr.y]="padT" [attr.width]="band()" [attr.height]="plotH()" fill="transparent" />
                @if (p.value > 0) {
                  <path [attr.d]="column(i, p.value)" [attr.fill]="active() === i ? '#4338ca' : '#4f46e5'" />
                }
              </g>
            }
            <!-- baseline -->
            <line [attr.x1]="padL" [attr.x2]="width()" [attr.y1]="y(0)" [attr.y2]="y(0)" stroke="#c7cbe3" stroke-width="1" />
            <!-- sparse x labels -->
            @for (i of xLabelIdx(); track i) {
              <text [attr.x]="bandX(i) + band() / 2" [attr.y]="height() - 4" text-anchor="middle" class="fill-muted text-[11px]">{{ shortLabel(points()[i].label) }}</text>
            }
          </svg>
        }

        @if (active() !== null && points()[active()!]; as p) {
          <div
            class="pointer-events-none absolute z-10 -translate-x-1/2 -translate-y-full rounded-xl bg-navy px-3 py-2 text-xs text-white shadow-xl"
            [style.left.px]="tipX()"
            [style.top.px]="tipY()"
            role="status"
          >
            <p class="font-display text-base font-bold">{{ format()(p.value) }}</p>
            <p class="text-brand-soft">{{ shortLabel(p.label) }}</p>
          </div>
        }
      </div>
      <details class="mt-2 text-sm">
        <summary class="cursor-pointer text-xs font-semibold text-muted hover:text-brand">View as table</summary>
        <div class="mt-2 max-h-56 overflow-auto rounded-xl border border-line">
          <table class="w-full text-left text-sm">
            <thead class="sticky top-0 bg-surface text-xs text-muted">
              <tr><th class="px-3 py-2 font-semibold">{{ labelHeader() }}</th><th class="px-3 py-2 text-right font-semibold">{{ title() }}</th></tr>
            </thead>
            <tbody class="divide-y divide-line">
              @for (p of points(); track p.label) {
                <tr><td class="px-3 py-1.5">{{ shortLabel(p.label) }}</td><td class="px-3 py-1.5 text-right tabular-nums">{{ format()(p.value) }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  `,
})
export class ColumnChart {
  readonly points = input.required<ChartPoint[]>();
  readonly title = input.required<string>();
  readonly labelHeader = input('Day');
  readonly format = input<(v: number) => string>((v) => v.toLocaleString('en-IN'));

  private readonly host = inject(ElementRef<HTMLElement>);
  protected readonly width = signal(0);
  protected readonly active = signal<number | null>(null);
  protected readonly shortLabel = shortLabel;

  protected readonly padL = 44;
  protected readonly padT = 8;
  private readonly padB = 22;

  protected readonly height = computed(() => (this.width() < 480 ? 192 : 224));
  protected readonly plotH = computed(() => this.height() - this.padT - this.padB);
  protected readonly max = computed(() => niceMax(Math.max(0, ...this.points().map((p) => p.value))));
  protected readonly ticks = computed(() => [0, this.max() / 2, this.max()]);
  protected readonly band = computed(() => (this.width() - this.padL) / Math.max(1, this.points().length));
  private readonly colW = computed(() => Math.max(3, Math.min(24, this.band() - 2)));

  /** Label roughly every 70px so text never collides. */
  protected readonly xLabelIdx = computed(() => {
    const n = this.points().length;
    if (!n) return [];
    const step = Math.max(1, Math.ceil(70 / Math.max(1, this.band())));
    const idx: number[] = [];
    for (let i = 0; i < n; i += step) idx.push(i);
    if (idx[idx.length - 1] !== n - 1 && n - 1 - idx[idx.length - 1] >= step / 2) idx.push(n - 1);
    return idx;
  });

  protected readonly tipX = computed(() => {
    const i = this.active();
    return i === null ? 0 : Math.min(Math.max(this.bandX(i) + this.band() / 2, 50), this.width() - 50);
  });
  protected readonly tipY = computed(() => {
    const i = this.active();
    return i === null ? 0 : this.y(this.points()[i]?.value ?? 0) - 8;
  });

  constructor() {
    afterNextRender(() => {
      const el = this.host.nativeElement.querySelector('div') as HTMLElement;
      const ro = new ResizeObserver(([entry]) => this.width.set(Math.floor(entry.contentRect.width)));
      ro.observe(el);
      this.destroy.onDestroy(() => ro.disconnect());
    });
  }

  private readonly destroy = inject(DestroyRef);

  protected y(v: number): number {
    return this.padT + this.plotH() - (v / this.max()) * this.plotH();
  }

  protected bandX(i: number): number {
    return this.padL + i * this.band();
  }

  /** Column path: square at the baseline, 4px rounded data end. */
  protected column(i: number, v: number): string {
    const w = this.colW();
    const x = this.bandX(i) + (this.band() - w) / 2;
    const top = this.y(v);
    const base = this.y(0);
    const r = Math.min(4, w / 2, base - top);
    return `M${x},${base} V${top + r} Q${x},${top} ${x + r},${top} H${x + w - r} Q${x + w},${top} ${x + w},${top + r} V${base} Z`;
  }
}

export interface BarItem {
  label: string;
  value: number;
  hint?: string;
}

/** Ranked horizontal bars with the value at the tip (top categories, statuses…). Plain HTML, readable without hover. */
@Component({
  selector: 'pg-bar-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <ul class="space-y-3">
      @for (item of items(); track item.label) {
        <li>
          <div class="flex items-baseline justify-between gap-3 text-sm">
            <span class="min-w-0 truncate font-medium">{{ item.label }}</span>
            <span class="shrink-0 tabular-nums text-ink">{{ format()(item.value) }}@if (item.hint) {<span class="text-muted"> · {{ item.hint }}</span>}</span>
          </div>
          <div class="mt-1.5 h-2 rounded-full bg-brand-mist/60">
            <div class="h-2 rounded-full bg-brand" [style.width.%]="pct(item.value)"></div>
          </div>
        </li>
      } @empty {
        <li class="text-sm text-muted">No data for this period.</li>
      }
    </ul>
  `,
})
export class BarList {
  readonly items = input.required<BarItem[]>();
  readonly format = input<(v: number) => string>((v) => v.toLocaleString('en-IN'));
  private readonly max = computed(() => Math.max(1, ...this.items().map((i) => i.value)));
  protected pct(v: number): number {
    return v <= 0 ? 0 : Math.max(2, (v / this.max()) * 100);
  }
}
