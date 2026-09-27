import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { LucideDynamicIcon } from '@lucide/angular';

import { categoryIcon, iconNameForSlug } from './icons';

/** Gradient used when a category has no photo yet. */
const FALLBACK_GRADIENT: Record<string, string> = {
  'pest-control': 'from-emerald-600 via-teal-600 to-cyan-700',
  'home-security': 'from-slate-700 via-indigo-800 to-navy',
};

/**
 * The picture for a category: its photo (lazy-loaded, zooms on hover) or, if it has none,
 * an illustrated gradient panel with a big icon. Fills its parent; the parent sets the size.
 */
@Component({
  selector: 'pg-category-visual',
  imports: [LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'absolute inset-0 block overflow-hidden' },
  template: `
    @if (imageUrl()) {
      <img
        [src]="imageUrl()"
        [alt]="alt()"
        loading="lazy"
        decoding="async"
        class="size-full object-cover transition duration-700 ease-out group-hover:scale-105"
      />
    } @else {
      <div class="relative size-full bg-linear-to-br" [class]="fallback(slug())">
        <svg
          [lucideIcon]="icon(iconName() ?? slugIcon(slug()))"
          [size]="160"
          [strokeWidth]="1"
          class="absolute -bottom-6 -right-6 text-white/15 transition duration-700 group-hover:scale-110"
          aria-hidden="true"
        ></svg>
        <svg [lucideIcon]="icon(iconName() ?? slugIcon(slug()))" [size]="56" [strokeWidth]="1.5" class="absolute left-6 top-6 text-gold" aria-hidden="true"></svg>
      </div>
    }
  `,
})
export class CategoryVisual {
  readonly imageUrl = input<string | null>(null);
  readonly slug = input('');
  readonly iconName = input<string | null>(null);
  readonly alt = input('');

  protected readonly icon = categoryIcon;
  protected readonly slugIcon = iconNameForSlug;

  protected fallback(slug: string): string {
    return FALLBACK_GRADIENT[slug] ?? 'from-brand via-indigo-700 to-navy';
  }
}
