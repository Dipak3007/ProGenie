import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { LucideDynamicIcon, LucideHeart as Heart } from '@lucide/angular';

import { API, errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { GenieCard as GenieCardModel } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { GenieCard } from '../../shared/ui/genie-card';
import { EmptyState, PageHead, Skeleton } from '../../shared/ui/kit';

@Component({
  selector: 'pg-favourites-page',
  imports: [RouterLink, LucideDynamicIcon, GenieCard, EmptyState, PageHead, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Favourite Genies" subtitle="The people you trust, one tap away." />

    <div class="mt-6">
      @if (list.value(); as genies) {
        <div class="grid gap-5 sm:grid-cols-2 2xl:grid-cols-3">
          @for (g of genies; track g.id) {
            <div class="relative">
              <pg-genie-card [genie]="g" />
              <button
                type="button"
                class="absolute right-3 top-14 z-10 grid size-10 place-items-center rounded-full bg-white/95 text-rose-600 shadow-lg hover:bg-white"
                [attr.aria-label]="'Remove ' + g.fullName + ' from favourites'"
                (click)="remove(g)"
              >
                <svg [lucideIcon]="Heart" [size]="18" class="fill-rose-600" aria-hidden="true"></svg>
              </button>
            </div>
          } @empty {
            <div class="sm:col-span-2 2xl:col-span-3">
              <pg-empty [icon]="Heart" title="No favourites yet" message="Tap the heart on a Genie's profile to save them here.">
                <a routerLink="/services" class="btn-primary">Find a Genie</a>
              </pg-empty>
            </div>
          }
        </div>
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="2" />
      }
    </div>
  `,
})
export default class FavouritesPage {
  private readonly api = inject(CustomerApi);
  private readonly toast = inject(ToastService);
  protected readonly list = httpResource<GenieCardModel[]>(() => `${API}/me/favourites`);

  protected remove(g: GenieCardModel): void {
    this.list.update((list) => list?.filter((x) => x.id !== g.id));
    this.api.removeFavourite(g.id).subscribe({
      next: () => this.toast.success(`${g.fullName} removed from favourites`),
      error: (err) => {
        this.toast.error(errorMessage(err));
        this.list.reload();
      },
    });
  }

  protected readonly Heart = Heart;
}
