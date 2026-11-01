import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { LucideCircleHelp as CircleHelp, LucideDynamicIcon, LucideSearch as SearchIcon } from '@lucide/angular';

import { API } from '../../core/api/api';
import { HELP_CATEGORY_LABEL, HelpApi } from '../../core/api/help-api';
import { HelpArticle, HelpCategory } from '../../core/api/models';
import { Markdown } from '../../shared/ui/markdown';
import { EmptyState, Skeleton } from '../../shared/ui/kit';

const VOTED_KEY = 'pg-help-voted';

/**
 * The help centre (Phase 3.5B): searchable answers grouped by category, for customers, Genies and guests. A link can
 * open one category (?category=GENIES) or one answer (#slug). "Was this helpful?" is remembered in this browser.
 */
@Component({
  selector: 'pg-help-page',
  imports: [RouterLink, LucideDynamicIcon, EmptyState, Markdown, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="bg-navy text-white">
      <div class="mx-auto max-w-4xl px-4 py-10 sm:py-14">
        <p class="flex items-center gap-2 text-sm font-semibold text-gold"><svg [lucideIcon]="CircleHelp" [size]="16" aria-hidden="true"></svg> Help centre</p>
        <h1 class="mt-2 text-3xl font-bold sm:text-4xl">How can we help?</h1>
        <form class="relative mt-5" role="search" (submit)="$event.preventDefault()">
          <label for="help-q" class="sr-only">Search the help centre</label>
          <svg [lucideIcon]="SearchIcon" [size]="18" class="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-muted" aria-hidden="true"></svg>
          <input
            id="help-q"
            type="search"
            class="field rounded-full py-3.5 pl-11 text-ink"
            placeholder="e.g. cancel, refund, start code, payouts"
            [value]="q()"
            (input)="q.set($any($event.target).value)"
          />
        </form>
      </div>
    </section>

    <div class="mx-auto max-w-4xl px-4 py-8">
      <div class="flex gap-2 overflow-x-auto pb-1" role="tablist" aria-label="Help topics">
        <button type="button" role="tab" class="chip-tab" [class.chip-tab-on]="!category()" [attr.aria-selected]="!category()" (click)="category.set(null)">All</button>
        @for (c of categories; track c) {
          <button type="button" role="tab" class="chip-tab" [class.chip-tab-on]="category() === c" [attr.aria-selected]="category() === c" (click)="category.set(c)">
            {{ labels[c] }}
          </button>
        }
      </div>

      @if (list.value(); as all) {
        @for (group of groups(); track group.category) {
          <section class="mt-8" [attr.aria-labelledby]="'h-' + group.category">
            <h2 [id]="'h-' + group.category" class="text-xl font-bold">{{ labels[group.category] }}</h2>
            <ul class="mt-3 space-y-2">
              @for (a of group.articles; track a.id) {
                <li class="card overflow-hidden" [id]="a.slug" data-testid="help-article">
                  <details [open]="openSlug() === a.slug || !!q().trim()" (toggle)="onToggle(a, $event)">
                    <summary class="flex min-h-12 cursor-pointer list-none items-center justify-between gap-3 px-5 py-3 font-semibold hover:bg-surface">
                      {{ a.question }}
                      <span class="text-muted" aria-hidden="true">+</span>
                    </summary>
                    <div class="border-t border-line px-5 py-4 text-sm">
                      <pg-markdown [text]="a.answer" />
                      <div class="mt-4 flex flex-wrap items-center gap-2 text-sm text-muted" data-testid="help-vote">
                        @if (voted().includes(a.id)) {
                          <span class="text-emerald-700">Thanks for telling us.</span>
                        } @else {
                          <span>Was this helpful?</span>
                          <button type="button" class="btn-ghost min-h-9 px-3" (click)="vote(a, true)">Yes</button>
                          <button type="button" class="btn-ghost min-h-9 px-3" (click)="vote(a, false)">No</button>
                        }
                      </div>
                    </div>
                  </details>
                </li>
              }
            </ul>
          </section>
        } @empty {
          <pg-empty class="mt-8 block" [icon]="CircleHelp" title="No answers match" [message]="'Nothing found for “' + q() + '”. Try other words, or contact us.'" />
        }
        <p class="mt-10 rounded-2xl bg-surface p-5 text-sm">
          Still stuck? Problems with a booking are fastest through <span class="font-semibold">Report a problem</span> on the booking itself. For anything else,
          <a routerLink="/" fragment="contact" class="font-semibold text-brand underline">contact us</a>.
        </p>
      } @else if (list.isLoading()) {
        <pg-skeleton class="mt-8 block" [rows]="5" />
      }
    </div>
  `,
  styles: `
    .chip-tab {
      flex-shrink: 0;
      min-height: 2.5rem;
      border-radius: 9999px;
      border: 1px solid var(--color-line);
      background: white;
      padding: 0 1rem;
      font-size: 0.875rem;
      font-weight: 600;
    }
    .chip-tab-on {
      border-color: var(--color-navy);
      background: var(--color-navy);
      color: white;
    }
    summary::-webkit-details-marker {
      display: none;
    }
  `,
})
export default class HelpPage {
  /** ?category=GENIES (the Genie console links here). */
  readonly categoryParam = input<string | undefined>(undefined, { alias: 'category' });

  private readonly api = inject(HelpApi);
  protected readonly list = httpResource<HelpArticle[]>(() => `${API}/help`);
  protected readonly labels = HELP_CATEGORY_LABEL;
  protected readonly categories = Object.keys(HELP_CATEGORY_LABEL) as HelpCategory[];
  protected readonly q = signal('');
  protected readonly category = signal<HelpCategory | null>(null);
  protected readonly openSlug = signal<string | null>(null);
  protected readonly voted = signal<number[]>(readVoted());

  /** Matching answers per category, in the help centre's order. */
  protected readonly groups = computed(() => {
    const words = this.q().trim().toLowerCase().split(/\s+/).filter(Boolean);
    const cat = this.category();
    const matches = (this.list.value() ?? []).filter(
      (a) => (!cat || a.category === cat) && words.every((w) => (a.question + ' ' + a.answer).toLowerCase().includes(w)),
    );
    return this.categories
      .map((c) => ({ category: c, articles: matches.filter((a) => a.category === c) }))
      .filter((g) => g.articles.length);
  });

  constructor() {
    effect(() => {
      const param = this.categoryParam();
      untracked(() => this.category.set(param && param in HELP_CATEGORY_LABEL ? (param as HelpCategory) : null));
    });
    // #slug opens that answer once the list has loaded
    effect(() => {
      if (!this.list.value()) return;
      const slug = decodeURIComponent(location.hash.replace(/^#/, ''));
      if (!slug) return;
      untracked(() => {
        this.openSlug.set(slug);
        setTimeout(() => document.getElementById(slug)?.scrollIntoView({ block: 'start' }));
      });
    });
  }

  protected onToggle(a: HelpArticle, event: Event): void {
    if ((event.target as HTMLDetailsElement).open) this.openSlug.set(a.slug);
  }

  protected vote(a: HelpArticle, helpful: boolean): void {
    this.voted.update((ids) => [...ids, a.id]);
    try {
      localStorage.setItem(VOTED_KEY, JSON.stringify(this.voted()));
    } catch {
      // private window or blocked storage: the vote still counts
    }
    this.api.vote(a.id, helpful).subscribe({ error: () => undefined });
  }

  protected readonly CircleHelp = CircleHelp;
  protected readonly SearchIcon = SearchIcon;
}

function readVoted(): number[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(VOTED_KEY) ?? '[]');
    return Array.isArray(parsed) ? parsed.filter((x) => typeof x === 'number') : [];
  } catch {
    return [];
  }
}
