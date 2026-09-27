import { ChangeDetectionStrategy, Component, inject, input, linkedSignal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  LucideArrowRight as ArrowRight,
  LucideClock as Clock,
  LucideDynamicIcon,
  LucideSearch as SearchIcon,
  LucideX as X,
} from '@lucide/angular';

import { API, query } from '../../core/api/api';
import { Category, SearchHit } from '../../core/api/models';
import { CategoryVisual } from '../../shared/ui/category-visual';
import { categoryIcon } from '../../shared/ui/icons';

@Component({
  selector: 'pg-services-page',
  imports: [RouterLink, CurrencyPipe, LucideDynamicIcon, CategoryVisual],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="relative overflow-hidden text-white">
      <img src="/images/hero/interiors.webp" alt="" class="absolute inset-0 size-full object-cover" />
      <div class="absolute inset-0 bg-linear-to-r from-navy via-navy/90 to-navy/50"></div>
      <div class="container-page relative py-14 sm:py-20">
        <p class="text-sm font-semibold uppercase tracking-widest text-gold">Ahmedabad</p>
        <h1 class="mt-2 text-4xl font-extrabold sm:text-6xl">All services</h1>
        <p class="mt-3 max-w-xl text-brand-mist">12 categories, 60 services, every one done by an admin-verified Genie.</p>
        <form class="mt-6 max-w-xl" role="search" (submit)="$event.preventDefault(); applySearch()">
          <label for="svc-search" class="sr-only">Search services</label>
          <div class="flex items-center gap-2 rounded-full bg-white p-1.5 shadow-2xl shadow-black/30">
            <span class="pl-3 text-muted" aria-hidden="true"><svg [lucideIcon]="SearchIcon" [size]="20"></svg></span>
            <input
              id="svc-search"
              class="min-w-0 flex-1 bg-transparent py-2.5 text-base text-ink placeholder:text-muted/70 focus:outline-none"
              placeholder="Search a service, e.g. “fan” or “tap”"
              [value]="term()"
              (input)="term.set($any($event.target).value)"
              autocomplete="off"
            />
            @if (term()) {
              <button type="button" class="grid size-9 place-items-center rounded-full text-muted hover:bg-surface" aria-label="Clear search" (click)="clear()">
                <svg [lucideIcon]="X" [size]="18" aria-hidden="true"></svg>
              </button>
            }
            <button type="submit" class="btn-gold min-h-11 px-5">Search</button>
          </div>
        </form>
      </div>
    </section>

    @if (q()) {
      <section class="container-page pt-10" aria-labelledby="results-h">
        <h2 id="results-h" class="text-2xl font-bold">Results for “{{ q() }}”</h2>
        @if (results.value(); as hits) {
          <ul class="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            @for (h of hits; track h.serviceId) {
              <li>
                <a
                  [routerLink]="['/services', h.categorySlug]"
                  [queryParams]="{ service: h.serviceId }"
                  class="card flex h-full items-start justify-between gap-3 p-4 transition hover:border-brand-soft"
                >
                  <span>
                    <span class="block font-semibold">{{ h.serviceName }}</span>
                    <span class="mt-1 block text-sm text-muted">{{ h.categoryName }}</span>
                    <span class="mt-2 inline-flex items-center gap-1 text-xs text-muted">
                      <svg [lucideIcon]="Clock" [size]="13" aria-hidden="true"></svg> ~{{ h.durationMinutes }} min
                    </span>
                  </span>
                  <span class="shrink-0 font-display text-lg font-bold">{{ h.basePrice | currency: 'INR' : 'symbol' : '1.0-0' }}</span>
                </a>
              </li>
            } @empty {
              <li class="card p-6 text-sm text-muted sm:col-span-2 lg:col-span-3">
                No service matches “{{ q() }}”. Try a simpler word, or browse the categories below.
              </li>
            }
          </ul>
        } @else if (results.isLoading()) {
          <div class="mt-4 h-24 animate-pulse rounded-(--radius-card) bg-white"></div>
        }
      </section>
    }

    <section class="container-page py-12 sm:py-16">
      <div class="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        @for (c of categories.value() ?? []; track c.id) {
          <a [routerLink]="['/services', c.slug]" class="group card overflow-hidden transition duration-300 hover:-translate-y-1 hover:shadow-xl hover:shadow-brand/10">
            <div class="relative h-48">
              <pg-category-visual [imageUrl]="c.imageUrl" [slug]="c.slug" [iconName]="c.icon" [alt]="c.name" />
              <div class="photo-shade absolute inset-0"></div>
              <span class="absolute bottom-4 left-4 grid size-11 place-items-center rounded-xl bg-white/95 text-brand-600 shadow-lg">
                <svg [lucideIcon]="icon(c.icon)" [size]="22" aria-hidden="true"></svg>
              </span>
            </div>
            <div class="p-5">
              <div class="flex items-start justify-between gap-3">
                <h2 class="text-xl font-bold">{{ c.name }}</h2>
                <span class="shrink-0 rounded-full bg-gold/15 px-3 py-1 text-sm font-semibold text-ink">
                  from {{ c.startingPrice | currency: 'INR' : 'symbol' : '1.0-0' }}
                </span>
              </div>
              <p class="mt-2 text-sm text-muted">{{ c.description }}</p>
              <p class="mt-4 inline-flex items-center gap-1 text-sm font-semibold text-brand transition group-hover:gap-2">
                {{ c.serviceCount }} services <svg [lucideIcon]="ArrowRight" [size]="16" aria-hidden="true"></svg>
              </p>
            </div>
          </a>
        }
      </div>
    </section>
  `,
})
export default class ServicesPage {
  /** ?q= from the URL (home page search or this page's box). */
  readonly q = input<string | undefined>(undefined);
  private readonly router = inject(Router);
  protected readonly term = linkedSignal(() => this.q() ?? '');

  protected readonly categories = httpResource<Category[]>(() => `${API}/categories`);
  protected readonly results = httpResource<SearchHit[]>(() => (this.q() ? `${API}/search${query({ q: this.q(), limit: 12 })}` : undefined));

  protected applySearch(): void {
    const q = this.term().trim();
    this.router.navigate([], { queryParams: q ? { q } : {}, replaceUrl: true });
  }

  protected clear(): void {
    this.term.set('');
    this.router.navigate([], { queryParams: {}, replaceUrl: true });
  }

  protected readonly SearchIcon = SearchIcon;
  protected readonly Clock = Clock;
  protected readonly X = X;
  protected readonly icon = categoryIcon;
  protected readonly ArrowRight = ArrowRight;
}
