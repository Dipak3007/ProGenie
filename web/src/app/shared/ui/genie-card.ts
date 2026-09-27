import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import {
  LucideArrowRight as ArrowRight,
  LucideBadgeCheck as BadgeCheck,
  LucideBriefcase as Briefcase,
  LucideDynamicIcon,
  LucideMapPin as MapPin,
} from '@lucide/angular';

import { GenieCard as GenieCardModel } from '../../core/api/models';
import { Avatar } from './avatar';
import { CategoryVisual } from './category-visual';
import { Rating } from './rating';

/** Genie listing card: category photo on top, overlapping avatar, key facts and price. */
@Component({
  selector: 'pg-genie-card',
  imports: [RouterLink, CurrencyPipe, LucideDynamicIcon, Avatar, CategoryVisual, Rating],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let g = genie();
    <a
      [routerLink]="['/genies', g.id]"
      class="group card flex h-full flex-col overflow-hidden transition duration-300 hover:-translate-y-1 hover:shadow-xl hover:shadow-brand/10"
    >
      <div class="relative h-32">
        <pg-category-visual [imageUrl]="g.coverImage" [slug]="g.categorySlug" [alt]="g.categories" />
        <div class="photo-shade absolute inset-0"></div>
        <span class="absolute left-4 top-4 chip bg-white/90 text-ink backdrop-blur">{{ g.categories }}</span>
        @if (g.online) {
          <span class="absolute right-4 top-4 chip bg-emerald-500 text-white">Online now</span>
        }
      </div>

      <div class="flex flex-1 flex-col px-5 pb-5">
        <div class="-mt-8 flex items-end justify-between gap-3">
          <pg-avatar [name]="g.fullName" [size]="64" [online]="g.online" />
          <pg-rating class="mb-1" [value]="g.avgRating" [count]="g.ratingCount" />
        </div>

        <h3 class="mt-3 flex items-center gap-1.5 text-lg font-semibold">
          <span class="truncate">{{ g.fullName }}</span>
          <svg [lucideIcon]="BadgeCheck" [size]="18" class="shrink-0 text-brand" title="Verified Genie"></svg>
        </h3>
        <p class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted">
          <span class="inline-flex items-center gap-1"><svg [lucideIcon]="Briefcase" [size]="14" aria-hidden="true"></svg>{{ g.experienceYears }} yrs</span>
          @if (g.baseArea) {
            <span class="inline-flex items-center gap-1"><svg [lucideIcon]="MapPin" [size]="14" aria-hidden="true"></svg>{{ g.baseArea }}</span>
          }
          <span>{{ g.completedJobs }} jobs</span>
        </p>

        @if (g.bio) {
          <p class="mt-3 line-clamp-2 text-sm text-ink/75">{{ g.bio }}</p>
        }

        <div class="mt-auto flex items-center justify-between gap-3 border-t border-line pt-4">
          <p class="text-sm text-muted">
            From <span class="font-display text-xl font-bold text-ink">{{ g.startingPrice | currency: 'INR' : 'symbol' : '1.0-0' }}</span>
          </p>
          <span class="inline-flex items-center gap-1 text-sm font-semibold text-brand transition group-hover:gap-2">
            View profile <svg [lucideIcon]="ArrowRight" [size]="16" aria-hidden="true"></svg>
          </span>
        </div>
      </div>
    </a>
  `,
})
export class GenieCard {
  readonly genie = input.required<GenieCardModel>();

  protected readonly BadgeCheck = BadgeCheck;
  protected readonly MapPin = MapPin;
  protected readonly Briefcase = Briefcase;
  protected readonly ArrowRight = ArrowRight;
}
