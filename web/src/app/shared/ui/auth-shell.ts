import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { LucideBadgeCheck as BadgeCheck, LucideDynamicIcon } from '@lucide/angular';

/**
 * Split layout for login / sign-up: a photo panel with a message on large screens,
 * the form (projected content) on the right. On phones only the form is shown.
 */
@Component({
  selector: 'pg-auth-shell',
  imports: [LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid min-h-fold lg:grid-cols-2">
      <aside class="relative hidden overflow-hidden lg:block">
        <img [src]="image()" alt="" class="absolute inset-0 size-full object-cover object-[center_20%]" />
        <div class="absolute inset-0 bg-linear-to-t from-navy via-navy/60 to-navy/10"></div>
        <div class="absolute inset-x-0 bottom-0 p-12 text-white short:p-10 tiny:p-8">
          <h2 class="max-w-md text-4xl font-extrabold leading-tight tiny:text-3xl">{{ headline() }}</h2>
          <ul class="mt-6 space-y-2 text-brand-mist tiny:mt-4 tiny:space-y-1.5">
            @for (point of points(); track point) {
              <li class="flex items-center gap-2"><svg [lucideIcon]="BadgeCheck" [size]="18" class="text-gold" aria-hidden="true"></svg>{{ point }}</li>
            }
          </ul>
        </div>
      </aside>
      <div class="flex items-center justify-center bg-surface px-4 py-10 sm:px-6 short:py-6 tiny:py-4">
        <ng-content />
      </div>
    </div>
  `,
})
export class AuthShell {
  readonly image = input('/images/hero/hero-ac.webp');
  readonly headline = input('Home jobs, done right.');
  readonly points = input<string[]>([]);
  protected readonly BadgeCheck = BadgeCheck;
}
