import { ChangeDetectionStrategy, Component, computed, input, linkedSignal, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import {
  LucideClock as Clock,
  LucideDynamicIcon,
} from '@lucide/angular';

import { API, geniesUrl } from '../../core/api/api';
import { CategoryDetail, GenieCard as GenieCardModel, GenieSort } from '../../core/api/models';
import { CategoryVisual } from '../../shared/ui/category-visual';
import { GenieCard } from '../../shared/ui/genie-card';
import { categoryIcon } from '../../shared/ui/icons';

/** /services/:slug: the services in a category and the Genies who offer them. */
@Component({
  selector: 'pg-category-page',
  imports: [RouterLink, CurrencyPipe, LucideDynamicIcon, CategoryVisual, GenieCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (detail.value(); as d) {
      <section class="relative overflow-hidden text-white">
        <pg-category-visual [imageUrl]="d.category.imageUrl" [slug]="d.category.slug" [iconName]="d.category.icon" [alt]="d.category.name" />
        <div class="absolute inset-0 bg-linear-to-r from-navy via-navy/85 to-navy/30"></div>
        <div class="container-page relative py-14 sm:py-24">
          <a routerLink="/services" class="text-sm text-brand-soft hover:text-white">← All services</a>
          <div class="mt-5 flex items-center gap-4">
            <span class="grid size-14 place-items-center rounded-2xl bg-white/10 text-gold backdrop-blur">
              <svg [lucideIcon]="icon(d.category.icon)" [size]="28" aria-hidden="true"></svg>
            </span>
            <h1 class="text-4xl font-extrabold sm:text-6xl">{{ d.category.name }}</h1>
          </div>
          <p class="mt-4 max-w-xl text-lg text-brand-mist">{{ d.category.description }}</p>
          <div class="mt-6 flex flex-wrap gap-2 text-sm">
            <span class="chip bg-white/10 py-1.5 text-white">{{ d.services.length }} services</span>
            <span class="chip bg-white/10 py-1.5 text-white">From {{ d.category.startingPrice | currency: 'INR' : 'symbol' : '1.0-0' }}</span>
            <span class="chip bg-gold py-1.5 text-ink">Verified Genies only</span>
          </div>
        </div>
      </section>

      <div class="container-page grid gap-10 pt-12 pb-20 lg:grid-cols-[1fr_2fr]">
        <!-- Services -->
        <section aria-labelledby="svc-heading">
          <h2 id="svc-heading" class="text-2xl font-bold">Services</h2>
          <ul class="mt-4 grid gap-3">
            @for (s of d.services; track s.id) {
              <li>
                <button
                  type="button"
                  class="card block w-full border-l-4 p-4 text-left transition hover:border-brand-soft"
                  [class]="s.id === serviceFilter() ? 'border-l-brand ring-2 ring-brand' : 'border-l-gold'"
                  [attr.aria-pressed]="s.id === serviceFilter()"
                  (click)="toggleService(s.id)"
                >
                <div class="flex items-start justify-between gap-3">
                  <div>
                    <p class="font-semibold">{{ s.name }}</p>
                    <p class="mt-1 text-sm text-muted">{{ s.description }}</p>
                    <p class="mt-2 inline-flex items-center gap-1 text-xs text-muted">
                      <svg [lucideIcon]="Clock" [size]="14" aria-hidden="true"></svg> ~{{ s.durationMinutes }} min
                    </p>
                  </div>
                  <p class="shrink-0 font-display text-lg font-bold">{{ s.basePrice | currency: 'INR' : 'symbol' : '1.0-0' }}</p>
                </div>
                </button>
              </li>
            }
          </ul>
          <p class="mt-3 text-xs text-muted">Base prices. Genies may set their own price; travel fee depends on distance. Tap a service to see only the Genies who offer it.</p>
        </section>

        <!-- Genies -->
        <section aria-labelledby="genies-heading">
          <div class="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 id="genies-heading" class="text-2xl font-bold">Available Genies</h2>
              @if (selectedService(); as ss) {
                <p class="mt-1 flex flex-wrap items-center gap-2 text-sm text-muted">
                  Offering <span class="chip">{{ ss.name }}</span>
                  <button type="button" class="font-semibold text-brand hover:underline" (click)="serviceFilter.set(null)">Show all</button>
                </p>
              }
            </div>
            <label class="flex items-center gap-2 text-sm">
              <span class="text-muted">Sort by</span>
              <select class="field w-auto py-2" [value]="sort()" (change)="sort.set($any($event.target).value)">
                <option value="RATING">Top rated</option>
                <option value="PRICE">Lowest price</option>
                <option value="EXPERIENCE">Most experienced</option>
              </select>
            </label>
          </div>
          <div class="mt-4 grid gap-4 md:grid-cols-2">
            @for (g of genies.value() ?? []; track g.id) {
              <pg-genie-card [genie]="g" />
            } @empty {
              @if (!genies.isLoading()) {
                <p class="card p-6 text-sm text-muted md:col-span-2">
                  {{ serviceFilter() ? 'No Genie offers this service yet. Try another service.' : 'No Genies in this category yet. Check back soon!' }}
                </p>
              }
            }
          </div>
        </section>
      </div>
    } @else if (detail.error()) {
      <div class="container-page py-24 text-center">
        <h1 class="text-3xl font-bold">Category not found</h1>
        <a routerLink="/services" class="btn-primary mt-6">See all services</a>
      </div>
    } @else {
      <div class="container-page py-24"><div class="card h-40 animate-pulse"></div></div>
    }
  `,
})
export default class CategoryPage {
  /** Bound from the :slug route param (withComponentInputBinding). */
  readonly slug = input.required<string>();

  /** Optional ?service=<id> (from search results). */
  readonly service = input<string | undefined>(undefined);

  protected readonly sort = signal<GenieSort>('RATING');
  protected readonly serviceFilter = linkedSignal<number | null>(() => (this.service() ? Number(this.service()) : null));
  protected readonly detail = httpResource<CategoryDetail>(() => `${API}/categories/${encodeURIComponent(this.slug())}`);
  protected readonly genies = httpResource<GenieCardModel[]>(() => geniesUrl(this.slug(), this.sort(), 20, this.serviceFilter()));
  protected readonly selectedService = computed(() => this.detail.value()?.services.find((s) => s.id === this.serviceFilter()) ?? null);

  protected toggleService(id: number): void {
    this.serviceFilter.set(this.serviceFilter() === id ? null : id);
    // On one-column layouts the Genie list is below the services: bring it into view.
    if (window.matchMedia('(max-width: 1023px)').matches) {
      document.getElementById('genies-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }
  protected readonly title = computed(() => this.detail.value()?.category.name ?? '');

  protected readonly icon = categoryIcon;
  protected readonly Clock = Clock;
}
