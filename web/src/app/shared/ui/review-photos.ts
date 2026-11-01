import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Photos of the finished work in a review (Phase 3.5A): small thumbnails that open the full photo in a new tab.
 * The links are stable API paths that redirect to the file.
 */
@Component({
  selector: 'pg-review-photos',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (urls().length) {
      <ul class="mt-3 flex flex-wrap gap-2" aria-label="Photos of the finished work" data-testid="review-photos">
        @for (url of urls(); track url) {
          <li>
            <a [href]="url" target="_blank" rel="noopener" class="block overflow-hidden rounded-xl ring-1 ring-line hover:ring-brand">
              <img [src]="url" [alt]="'Photo ' + ($index + 1) + ' of the finished work'" class="size-20 object-cover" loading="lazy" />
            </a>
          </li>
        }
      </ul>
    }
  `,
})
export class ReviewPhotos {
  readonly urls = input<string[]>([]);
}
