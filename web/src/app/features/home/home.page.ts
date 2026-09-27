import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  LucideArrowRight as ArrowRight,
  LucideBadgeCheck as BadgeCheck,
  LucideCalendarCheck as CalendarCheck,
  LucideDynamicIcon,
  LucideHandCoins as HandCoins,
  LucideIndianRupee as IndianRupee,
  LucideMapPin as MapPin,
  LucideSearch as Search,
  LucideShieldCheck as ShieldCheck,
  LucideSparkles as Sparkles,
  LucideStar as Star,
  LucideWand as Wand,
} from '@lucide/angular';

import { API, geniesUrl } from '../../core/api/api';
import { Category, GenieCard as GenieCardModel } from '../../core/api/models';
import { CategoryVisual } from '../../shared/ui/category-visual';
import { GenieCard } from '../../shared/ui/genie-card';
import { categoryIcon } from '../../shared/ui/icons';
import { ContactSection } from './contact-section';

@Component({
  selector: 'pg-home-page',
  imports: [RouterLink, CurrencyPipe, LucideDynamicIcon, CategoryVisual, GenieCard, ContactSection],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- ============================ HERO ============================ -->
    <section class="lamp-glow relative overflow-hidden text-white lg:flex lg:min-h-fold lg:items-center">
      <div class="container-page grid items-center gap-12 py-14 sm:py-20 lg:grid-cols-[1.05fr_1fr] lg:gap-10 lg:py-12 lg:short:py-8 lg:tiny:py-6">
        <div>
          <span class="chip bg-white/10 text-gold">
            <svg [lucideIcon]="MapPin" [size]="14" aria-hidden="true"></svg> Now live in Ahmedabad
          </span>
          <h1 class="mt-5 text-[2.6rem] font-extrabold leading-[1.02] sm:text-6xl lg:text-7xl lg:short:mt-4 lg:short:text-6xl lg:tiny:text-[3.25rem]">
            Your wish,<br />
            <span class="bg-linear-to-r from-gold via-amber-300 to-gold bg-clip-text text-transparent">handled.</span>
          </h1>
          <p class="mt-5 max-w-xl text-base text-brand-mist sm:text-lg lg:short:mt-4 lg:tiny:text-base">
            Verified electricians, plumbers, cleaners and 50+ home services at your doorstep. See the full price, travel included, before
            you book.
          </p>

          <form class="mt-8 max-w-xl lg:short:mt-6 lg:tiny:mt-5" role="search" (submit)="$event.preventDefault(); goToFirstMatch()">
            <label for="hero-search" class="sr-only">What do you need help with?</label>
            <div class="flex items-center gap-2 rounded-full bg-white p-1.5 shadow-2xl shadow-black/30">
              <span class="pl-4 text-muted" aria-hidden="true"><svg [lucideIcon]="SearchIcon" [size]="20"></svg></span>
              <input
                id="hero-search"
                class="min-w-0 flex-1 bg-transparent py-3 text-base text-ink placeholder:text-muted/70 focus:outline-none"
                placeholder="Try “fan”, “AC service” or “cleaning”"
                [value]="query()"
                (input)="query.set($any($event.target).value)"
                autocomplete="off"
              />
              <button type="submit" class="btn-gold min-h-12 px-6">Search</button>
            </div>
          </form>

          <div class="mt-4 flex max-w-xl flex-wrap gap-2 lg:tiny:mt-3">
            @for (c of suggestions(); track c.id) {
              <a [routerLink]="['/services', c.slug]" class="chip bg-white/10 py-1.5 text-white transition hover:bg-white/20">{{ c.name }}</a>
            }
          </div>

          <ul class="mt-9 lg:short:mt-7 lg:tiny:mt-5 flex max-w-xl flex-col gap-3 text-sm text-brand-mist sm:flex-row sm:flex-wrap sm:gap-x-6">
            <li class="flex items-center gap-2"><svg [lucideIcon]="BadgeCheck" [size]="18" class="text-gold" aria-hidden="true"></svg> Admin-verified Genies</li>
            <li class="flex items-center gap-2"><svg [lucideIcon]="IndianRupee" [size]="18" class="text-gold" aria-hidden="true"></svg> Upfront pricing</li>
            <li class="flex items-center gap-2"><svg [lucideIcon]="HandCoins" [size]="18" class="text-gold" aria-hidden="true"></svg> Pay after the job</li>
          </ul>
        </div>

        <!-- Photo collage -->
        <div class="relative mx-auto w-full max-w-lg lg:w-fit lg:max-w-full">
          <div
            class="grid grid-cols-5 grid-rows-6 gap-3 sm:gap-4 lg:h-[clamp(22rem,calc(100dvh-var(--header-h)-8rem),42rem)] lg:max-w-full lg:short:h-[clamp(22rem,calc(100dvh-var(--header-h)-6rem),42rem)] lg:tiny:h-[clamp(20rem,calc(100dvh-var(--header-h)-4.5rem),42rem)]"
            style="aspect-ratio: 5 / 5.2"
          >
            <div class="relative col-span-3 row-span-6 overflow-hidden rounded-[2rem] ring-1 ring-white/10">
              <img src="/images/hero/hero-ac.webp" alt="Technician servicing a split AC" class="size-full object-cover" fetchpriority="high" />
            </div>
            <div class="relative col-span-2 row-span-3 overflow-hidden rounded-[2rem] ring-1 ring-white/10">
              <img src="/images/hero/hero-appliance.webp" alt="Washing machine repair at home" class="size-full object-cover" />
            </div>
            <div class="relative col-span-2 row-span-3 overflow-hidden rounded-[2rem] ring-1 ring-white/10">
              <img src="/images/hero/hero-cleaning.webp" alt="Sofa deep cleaning" class="size-full object-cover" />
            </div>
          </div>

          <!-- floating cards -->
          <div class="float-slow absolute -left-3 top-8 rounded-2xl bg-white/95 p-3 text-ink shadow-2xl sm:-left-8 sm:p-4">
            <p class="flex items-center gap-1 text-gold" aria-hidden="true">
              @for (i of [1, 2, 3, 4, 5]; track i) {
                <svg [lucideIcon]="Star" [size]="14" class="fill-gold"></svg>
              }
            </p>
            <p class="mt-1 text-sm font-semibold">Rated by real customers</p>
            <p class="text-xs text-muted">Only after a completed job</p>
          </div>

          <div class="float-slower absolute -bottom-5 right-2 flex items-center gap-3 rounded-2xl bg-white/95 p-3 pr-5 text-ink shadow-2xl sm:-right-6">
            <div class="flex -space-x-3">
              @for (p of people; track p) {
                <img [src]="p" alt="" class="size-10 rounded-full object-cover ring-2 ring-white" />
              }
            </div>
            <div>
              <p class="text-sm font-semibold">Verified professionals</p>
              <p class="text-xs text-muted">KYC + skills checked by our team</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ======================= CATEGORY MARQUEE ======================= -->
    <div class="marquee overflow-hidden border-b border-line bg-white py-4" aria-hidden="true">
      <div class="marquee-track flex w-max gap-3">
        @for (c of marquee(); track $index) {
          <span class="inline-flex items-center gap-2 rounded-full border border-line px-4 py-2 text-sm font-medium text-ink/80">
            <svg [lucideIcon]="icon(c.icon)" [size]="16" class="text-brand" aria-hidden="true"></svg>{{ c.name }}
          </span>
        }
      </div>
    </div>

    <!-- ========================= CATEGORIES ========================= -->
    <section class="container-page py-16 sm:py-20" aria-labelledby="cat-heading">
      <div class="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p class="text-sm font-semibold uppercase tracking-widest text-brand">Services</p>
          <h2 id="cat-heading" class="mt-2 text-3xl font-bold sm:text-5xl">What can we help with?</h2>
        </div>
        <a routerLink="/services" class="btn-ghost">All services <svg [lucideIcon]="ArrowRight" [size]="16" aria-hidden="true"></svg></a>
      </div>

      @if (categories.isLoading()) {
        <div class="mt-10 grid grid-cols-2 gap-3 sm:gap-4 md:grid-cols-4">
          @for (i of skeleton; track i) {
            <div class="h-44 animate-pulse rounded-3xl bg-line/60 sm:h-56"></div>
          }
        </div>
      } @else if (categories.error()) {
        <p class="card mt-10 p-6 text-sm text-red-700">Could not load categories. Make sure the API is running on port 8080.</p>
      } @else {
        <div class="mt-10 grid auto-rows-[11rem] grid-cols-2 gap-3 sm:auto-rows-[14rem] sm:gap-4 md:grid-cols-4">
          @for (c of categories.value(); track c.id; let i = $index) {
            <a
              [routerLink]="['/services', c.slug]"
              class="group relative overflow-hidden rounded-3xl bg-navy shadow-(--shadow-card)"
              [class]="i === 0 ? 'md:col-span-2 md:row-span-2' : i === 11 ? 'md:col-span-2' : ''"
            >
              <pg-category-visual [imageUrl]="c.imageUrl" [slug]="c.slug" [iconName]="c.icon" [alt]="c.name" />
              <div class="photo-shade absolute inset-0"></div>
              <div class="absolute inset-x-0 bottom-0 p-4 text-white sm:p-5">
                <p class="font-display font-bold leading-tight" [class]="i === 0 ? 'text-2xl sm:text-4xl' : 'text-lg sm:text-xl'">{{ c.name }}</p>
                <p class="mt-1 text-xs text-white/80 sm:text-sm">From {{ c.startingPrice | currency: 'INR' : 'symbol' : '1.0-0' }}</p>
              </div>
              <span
                class="absolute right-3 top-3 grid size-9 translate-y-1 place-items-center rounded-full bg-white/90 text-ink opacity-0 transition group-hover:translate-y-0 group-hover:opacity-100"
                aria-hidden="true"
              >
                <svg [lucideIcon]="ArrowRight" [size]="16"></svg>
              </span>
            </a>
          }
        </div>
      }
    </section>

    <!-- ========================= WHY PROGENIE ========================= -->
    <section class="bg-white py-16 sm:py-20" aria-labelledby="why-heading">
      <div class="container-page grid items-center gap-12 lg:grid-cols-2">
        <div class="relative">
          <img src="/images/hero/interiors.webp" alt="Beautifully maintained homes" class="aspect-[4/3] w-full rounded-[2rem] object-cover shadow-2xl" loading="lazy" />
          <div class="absolute -bottom-6 left-6 right-6 rounded-2xl bg-navy p-5 text-white shadow-2xl sm:left-auto sm:w-72">
            <p class="font-display text-3xl font-extrabold text-gold">95%</p>
            <p class="mt-1 text-sm text-brand-mist">of every service fee goes to the Genie who did the work.</p>
          </div>
        </div>
        <div>
          <p class="text-sm font-semibold uppercase tracking-widest text-brand">Why ProGenie</p>
          <h2 id="why-heading" class="mt-2 text-3xl font-bold sm:text-5xl">Help you can trust, prices you can see</h2>
          <ul class="mt-8 grid gap-5 sm:grid-cols-2">
            @for (v of values; track v.title) {
              <li class="rounded-2xl border border-line p-5">
                <span class="grid size-11 place-items-center rounded-xl bg-brand-mist text-brand-600">
                  <svg [lucideIcon]="v.icon" [size]="22" aria-hidden="true"></svg>
                </span>
                <h3 class="mt-4 text-lg font-semibold">{{ v.title }}</h3>
                <p class="mt-1 text-sm text-muted">{{ v.body }}</p>
              </li>
            }
          </ul>
        </div>
      </div>
    </section>

    <!-- ========================= TOP GENIES ========================= -->
    <section class="py-16 sm:py-20" aria-labelledby="genie-heading">
      <div class="container-page">
        <div class="flex flex-wrap items-end justify-between gap-4">
          <div>
            <p class="text-sm font-semibold uppercase tracking-widest text-brand">Top rated</p>
            <h2 id="genie-heading" class="mt-2 text-3xl font-bold sm:text-5xl">Genies near you</h2>
          </div>
          <p class="max-w-sm text-sm text-muted">Ratings come only from customers who completed a booking.</p>
        </div>
        <div class="mt-10 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
          @for (g of topGenies.value() ?? []; track g.id) {
            <pg-genie-card [genie]="g" />
          }
        </div>
      </div>
    </section>

    <!-- ========================= HOW IT WORKS ========================= -->
    <section class="bg-white py-16 sm:py-20" aria-labelledby="how-heading">
      <div class="container-page">
        <p class="text-center text-sm font-semibold uppercase tracking-widest text-brand">Simple as a wish</p>
        <h2 id="how-heading" class="mt-2 text-center text-3xl font-bold sm:text-5xl">How ProGenie works</h2>
        <ol class="relative mt-14 grid gap-10 md:grid-cols-3 md:gap-6">
          <li class="absolute left-[16%] right-[16%] top-8 hidden border-t-2 border-dashed border-brand-soft md:block" aria-hidden="true"></li>
          @for (step of steps; track step.title; let i = $index) {
            <li class="relative text-center">
              <span class="mx-auto grid size-16 place-items-center rounded-2xl bg-navy text-gold shadow-xl shadow-brand/20">
                <svg [lucideIcon]="step.icon" [size]="28" aria-hidden="true"></svg>
              </span>
              <p class="mt-5 text-xs font-bold uppercase tracking-widest text-brand">Step {{ i + 1 }}</p>
              <h3 class="mt-1 text-xl font-semibold">{{ step.title }}</h3>
              <p class="mx-auto mt-2 max-w-xs text-sm text-muted">{{ step.body }}</p>
            </li>
          }
        </ol>
      </div>
    </section>

    <!-- ========================= COMING SOON ========================= -->
    <section class="container-page py-16 sm:py-20" aria-labelledby="soon-heading">
      <p class="text-sm font-semibold uppercase tracking-widest text-brand">Coming soon</p>
      <h2 id="soon-heading" class="mt-2 text-3xl font-bold sm:text-5xl">Care for the whole family</h2>
      <div class="mt-10 grid gap-4 sm:grid-cols-3">
        @for (s of comingSoon; track s.title) {
          <article class="group relative h-64 overflow-hidden rounded-3xl">
            <img [src]="s.image" [alt]="s.title" loading="lazy" class="size-full object-cover transition duration-700 group-hover:scale-105" />
            <div class="photo-shade absolute inset-0"></div>
            <span class="chip absolute left-4 top-4 bg-gold text-ink">Coming soon</span>
            <div class="absolute inset-x-0 bottom-0 p-5 text-white">
              <h3 class="text-2xl font-bold">{{ s.title }}</h3>
              <p class="mt-1 text-sm text-white/80">{{ s.body }}</p>
            </div>
          </article>
        }
      </div>
    </section>

    <!-- ========================= BECOME A GENIE ========================= -->
    <section class="container-page">
      <div class="lamp-glow relative grid items-center overflow-hidden rounded-[2rem] text-white md:grid-cols-[1.3fr_1fr]">
        <div class="p-8 sm:p-12">
          <span class="chip bg-white/10 text-gold"><svg [lucideIcon]="Wand" [size]="14" aria-hidden="true"></svg> For professionals</span>
          <h2 class="mt-4 text-3xl font-bold sm:text-5xl">Turn your skills into steady work</h2>
          <p class="mt-4 max-w-lg text-brand-mist">
            Set your own prices and working hours. Keep 95% of your service fee, plus 100% of travel fees and tips.
          </p>
          <a routerLink="/register" [queryParams]="{ role: 'GENIE' }" class="btn-gold mt-8 min-h-12 px-7">
            <svg [lucideIcon]="ShieldCheck" [size]="18" aria-hidden="true"></svg> Become a Genie
          </a>
        </div>
        <img src="/images/hero/genie-join.webp" alt="A smiling ProGenie professional" loading="lazy" class="hidden h-full max-h-[26rem] w-full object-cover object-top md:block" />
      </div>
    </section>

    <pg-contact-section />
  `,
})
export default class HomePage {
  private readonly router = inject(Router);

  protected readonly categories = httpResource<Category[]>(() => `${API}/categories`);
  protected readonly topGenies = httpResource<GenieCardModel[]>(() => geniesUrl(undefined, 'RATING', 6));

  protected readonly query = signal('');
  protected readonly matches = computed(() => {
    const q = this.query().trim().toLowerCase();
    const all = this.categories.value() ?? [];
    return q ? all.filter((c) => `${c.name} ${c.description ?? ''}`.toLowerCase().includes(q)).slice(0, 5) : [];
  });
  /** Popular picks when the box is empty, live matches while typing. */
  protected readonly suggestions = computed(() =>
    this.query().trim() ? this.matches() : (this.categories.value() ?? []).slice(0, 4),
  );
  /** Doubled list so the CSS marquee loops seamlessly. */
  protected readonly marquee = computed(() => {
    const all = this.categories.value() ?? [];
    return [...all, ...all];
  });

  protected readonly skeleton = Array.from({ length: 8 }, (_, i) => i);
  protected readonly people = [1, 2, 3, 4].map((n) => `/images/people/pro-${n}.webp`);
  protected readonly values = [
    { icon: BadgeCheck, title: 'Verified Genies', body: 'Every Genie passes ID and skill checks by our admin team before their first job.' },
    { icon: IndianRupee, title: 'Upfront pricing', body: 'Service price and travel fee are shown before you book. No surprises at the door.' },
    { icon: CalendarCheck, title: 'Your time slot', body: 'Pick a slot that suits you. Your Genie starts the job with a one-time code.' },
    { icon: HandCoins, title: 'Fair for everyone', body: 'Only 5% platform commission, so great professionals stay with us.' },
  ];
  protected readonly steps = [
    { icon: Search, title: 'Pick a service', body: 'Browse categories and compare Genies by rating, experience and price.' },
    { icon: CalendarCheck, title: 'Choose a time', body: 'See real free slots and the full price, including the travel fee.' },
    { icon: Sparkles, title: 'Relax', body: 'Your Genie arrives, gets the job done, and you pay when it is finished.' },
  ];
  protected readonly comingSoon = [
    { title: 'Elderly care', body: 'Trained caregivers for parents and grandparents.', image: '/images/coming-soon/elderly-care.webp' },
    { title: 'Child care', body: 'Trusted babysitters and nannies near you.', image: '/images/coming-soon/child-care.webp' },
    { title: 'Pet care', body: 'Walking, feeding and grooming for your pets.', image: '/images/coming-soon/pet-care.webp' },
  ];

  protected readonly icon = categoryIcon;
  protected readonly SearchIcon = Search;
  protected readonly BadgeCheck = BadgeCheck;
  protected readonly IndianRupee = IndianRupee;
  protected readonly HandCoins = HandCoins;
  protected readonly ArrowRight = ArrowRight;
  protected readonly ShieldCheck = ShieldCheck;
  protected readonly MapPin = MapPin;
  protected readonly Star = Star;
  protected readonly Wand = Wand;

  protected goToFirstMatch(): void {
    const first = this.matches()[0];
    const q = this.query().trim();
    if (first) {
      this.router.navigate(['/services', first.slug]);
    } else {
      // No category name matches: search individual services ("fan", "tap leak" …).
      this.router.navigate(['/services'], { queryParams: q ? { q } : {} });
    }
  }
}
