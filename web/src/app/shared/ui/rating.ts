import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import {
  LucideDynamicIcon,
  LucideStar as Star,
} from '@lucide/angular';

@Component({
  selector: 'pg-rating',
  imports: [LucideDynamicIcon, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="inline-flex items-center gap-1 text-sm">
      <svg [lucideIcon]="Star" [size]="16" class="fill-gold text-gold" aria-hidden="true"></svg>
      @if (count() > 0) {
        <span class="font-semibold text-ink">{{ value() | number: '1.1-1' }}</span>
        <span class="text-muted">({{ count() }})</span>
      } @else {
        <span class="text-muted">New</span>
      }
    </span>
  `,
})
export class Rating {
  readonly value = input(0);
  readonly count = input(0);
  protected readonly Star = Star;
}
