import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map } from 'rxjs';
import {
  LucideChevronLeft as ChevronLeft,
  LucideChevronRight as ChevronRight,
  LucideDynamicIcon,
  LucideIconInput,
  LucideInbox as InboxIcon,
  LucideLoaderCircle as Loader,
} from '@lucide/angular';

/** Friendly placeholder for empty lists, with an optional action projected inside. */
@Component({
  selector: 'pg-empty',
  imports: [LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-col items-center rounded-(--radius-card) border border-dashed border-line bg-white/60 px-6 py-10 text-center">
      <span class="grid size-12 place-items-center rounded-2xl bg-brand-mist text-brand-600">
        <svg [lucideIcon]="icon()" [size]="22" aria-hidden="true"></svg>
      </span>
      <p class="mt-4 font-semibold">{{ title() }}</p>
      @if (message()) {
        <p class="mt-1 max-w-sm text-sm text-muted">{{ message() }}</p>
      }
      <div class="mt-4 empty:hidden"><ng-content /></div>
    </div>
  `,
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly message = input<string | null>(null);
  readonly icon = input<LucideIconInput>(InboxIcon);
}

/** "Showing 1–10 of 42" with previous/next buttons. Pages are 0-based like the API. */
@Component({
  selector: 'pg-pager',
  imports: [LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (total() > size()) {
      <nav class="mt-4 flex items-center justify-between gap-3 text-sm" aria-label="Pagination">
        <p class="text-muted">{{ from() }}–{{ to() }} of {{ total() }}</p>
        <div class="flex gap-2">
          <button type="button" class="btn-ghost min-h-10 px-3" [disabled]="page() === 0" (click)="pageChange.emit(page() - 1)" aria-label="Previous page">
            <svg [lucideIcon]="ChevronLeft" [size]="18" aria-hidden="true"></svg>
          </button>
          <button type="button" class="btn-ghost min-h-10 px-3" [disabled]="to() >= total()" (click)="pageChange.emit(page() + 1)" aria-label="Next page">
            <svg [lucideIcon]="ChevronRight" [size]="18" aria-hidden="true"></svg>
          </button>
        </div>
      </nav>
    }
  `,
})
export class Pager {
  readonly page = input(0);
  readonly size = input(10);
  readonly total = input(0);
  readonly pageChange = output<number>();

  protected readonly from = computed(() => this.page() * this.size() + 1);
  protected readonly to = computed(() => Math.min((this.page() + 1) * this.size(), this.total()));
  protected readonly ChevronLeft = ChevronLeft;
  protected readonly ChevronRight = ChevronRight;
}

/** KPI tile: label, big value, optional hint. */
@Component({
  selector: 'pg-stat',
  imports: [LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div class="card h-full p-4 sm:p-5" [class.bg-navy]="dark()" [class.text-white]="dark()" [class.border-navy]="dark()">
      <div class="flex items-center justify-between gap-2">
        <p class="text-xs font-semibold uppercase tracking-wider" [class]="dark() ? 'text-brand-soft' : 'text-muted'">{{ label() }}</p>
        @if (icon(); as i) {
          <svg [lucideIcon]="i" [size]="18" [class]="dark() ? 'text-gold' : 'text-brand'" aria-hidden="true"></svg>
        }
      </div>
      <p class="mt-2 truncate font-display text-2xl font-bold sm:text-3xl">{{ value() }}</p>
      @if (hint()) {
        <p class="mt-1 text-xs" [class]="dark() ? 'text-brand-mist' : 'text-muted'">{{ hint() }}</p>
      }
    </div>
  `,
})
export class StatTile {
  readonly label = input.required<string>();
  readonly value = input.required<string | number>();
  readonly hint = input<string | null>(null);
  readonly icon = input<LucideIconInput | null>(null);
  readonly dark = input(false);
}

export interface TabItem<T extends string = string> {
  value: T;
  label: string;
  count?: number | null;
}

/** Segmented control; scrolls sideways on narrow screens instead of wrapping. */
@Component({
  selector: 'pg-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div class="-mx-4 overflow-x-auto px-4 sm:mx-0 sm:px-0" role="tablist" [attr.aria-label]="label()">
      <div class="inline-flex min-w-max gap-1 rounded-full border border-line bg-white p-1">
        @for (t of items(); track t.value) {
          <button
            type="button"
            role="tab"
            class="inline-flex min-h-9 items-center gap-2 rounded-full px-4 text-sm font-semibold transition"
            [class]="t.value === value() ? 'bg-navy text-white' : 'text-muted hover:text-ink'"
            [attr.aria-selected]="t.value === value()"
            (click)="valueChange.emit(t.value)"
          >
            {{ t.label }}
            @if (t.count) {
              <span class="rounded-full px-1.5 text-xs" [class]="t.value === value() ? 'bg-gold text-ink' : 'bg-brand-mist text-brand-600'">{{ t.count }}</span>
            }
          </button>
        }
      </div>
    </div>
  `,
})
export class Tabs {
  readonly items = input.required<TabItem[]>();
  readonly value = input.required<string>();
  readonly label = input('Filter');
  readonly valueChange = output<string>();
}

/** Inline spinner for buttons and loading rows. */
@Component({
  selector: 'pg-spinner',
  imports: [LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<svg [lucideIcon]="Loader" [size]="size()" class="animate-spin" aria-hidden="true"></svg>`,
})
export class Spinner {
  readonly size = input(18);
  protected readonly Loader = Loader;
}

/** Grey placeholder blocks while data loads. */
@Component({
  selector: 'pg-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-3" aria-busy="true" aria-label="Loading">
      @for (r of rowsArray(); track $index) {
        <div class="card animate-pulse p-5">
          <div class="h-4 w-1/3 rounded bg-surface"></div>
          <div class="mt-3 h-3 w-2/3 rounded bg-surface"></div>
        </div>
      }
    </div>
  `,
})
export class Skeleton {
  readonly rows = input(3);
  protected readonly rowsArray = computed(() => Array.from({ length: this.rows() }));
}

export interface NavItem {
  path: string;
  label: string;
  icon: LucideIconInput;
  exact?: boolean;
  badge?: number | null;
  /** Other URL prefixes that should highlight this item (e.g. job details under /genie/bookings). */
  also?: string[];
}

/**
 * Layout for the signed-in areas (account, Genie console, admin).
 * Desktop: sticky sidebar. Phones and tablets: a sticky, sideways-scrolling pill bar under the header.
 */
@Component({
  selector: 'pg-console-shell',
  imports: [RouterLink, LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container-page pb-20 pt-0 lg:grid lg:grid-cols-[15rem_minmax(0,1fr)] lg:gap-8 lg:pt-10">
      <aside class="sticky top-(--header-h) z-30 -mx-4 border-b border-line bg-surface/95 px-4 backdrop-blur sm:-mx-6 sm:px-6 lg:top-[calc(var(--header-h)+2.5rem)] lg:mx-0 lg:self-start lg:border-0 lg:bg-transparent lg:px-0 lg:backdrop-blur-none">
        <div class="hidden lg:block">
          <p class="text-xs font-semibold uppercase tracking-widest text-muted">{{ eyebrow() }}</p>
          <p class="mt-1 font-display text-xl font-bold">{{ title() }}</p>
        </div>
        <nav
          class="-mx-4 overflow-x-auto px-4 py-2 sm:-mx-6 sm:px-6 lg:mx-0 lg:mt-5 lg:overflow-visible lg:p-0"
          [attr.aria-label]="title()"
        >
          <ul class="flex min-w-max gap-1 lg:min-w-0 lg:flex-col">
            @for (item of items(); track item.path) {
              <li>
                <a
                  [routerLink]="item.path"
                  [class.!bg-navy]="active(item)"
                  [class.!text-white]="active(item)"
                  [attr.aria-current]="active(item) ? 'page' : null"
                  class="flex min-h-10 items-center gap-2.5 whitespace-nowrap rounded-full px-4 text-sm font-semibold text-muted transition hover:bg-white hover:text-ink lg:rounded-xl lg:px-3"
                >
                  <svg [lucideIcon]="item.icon" [size]="18" aria-hidden="true"></svg>
                  <span class="flex-1">{{ item.label }}</span>
                  @if (item.badge) {
                    <span class="rounded-full bg-gold px-2 text-xs font-bold text-ink">{{ item.badge }}</span>
                  }
                </a>
              </li>
            }
          </ul>
        </nav>
      </aside>
      <section class="mt-6 min-w-0 lg:mt-0">
        <ng-content />
      </section>
    </div>
  `,
})
export class ConsoleShell {
  private readonly router = inject(Router);
  private readonly url = toSignal(this.router.events.pipe(filter((e) => e instanceof NavigationEnd), map(() => this.router.url)), {
    initialValue: this.router.url,
  });

  protected active(item: NavItem): boolean {
    const path = this.url().split(/[?#]/)[0];
    if (item.exact) return path === item.path;
    return [item.path, ...(item.also ?? [])].some((p) => path === p || path.startsWith(p + '/'));
  }

  readonly eyebrow = input('');
  readonly title = input.required<string>();
  readonly items = input.required<NavItem[]>();
}

/** Page title row with optional actions on the right (stacks on phones). */
@Component({
  selector: 'pg-page-head',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div class="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div class="min-w-0">
        <h1 class="text-2xl font-bold sm:text-3xl">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="mt-1 text-sm text-muted sm:text-base">{{ subtitle() }}</p>
        }
      </div>
      <div class="flex flex-wrap gap-2 empty:hidden"><ng-content /></div>
    </div>
  `,
})
export class PageHead {
  readonly title = input.required<string>();
  readonly subtitle = input<string | null>(null);
}
