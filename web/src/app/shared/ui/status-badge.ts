import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { TONE_CLASSES, Tone, statusMeta } from '../format';

/** Coloured pill for any status the API returns (booking, payment, verification, user, message). */
@Component({
  selector: 'pg-status',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span
    class="inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-0.5 font-sans text-xs font-semibold tracking-normal ring-1 ring-inset"
    [class]="classes()"
    ><span class="size-1.5 rounded-full bg-current opacity-70" aria-hidden="true"></span>{{ text() }}</span
  >`,
})
export class StatusBadge {
  readonly status = input<string | null | undefined>(null);
  /** Overrides the label derived from the status. */
  readonly label = input<string | null>(null);
  readonly tone = input<Tone | null>(null);

  private readonly meta = computed(() => statusMeta(this.status()));
  protected readonly text = computed(() => this.label() ?? this.meta().label);
  protected readonly classes = computed(() => TONE_CLASSES[this.tone() ?? this.meta().tone]);
}
