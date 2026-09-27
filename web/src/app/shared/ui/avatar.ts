import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Warm, on-brand gradients; each person always gets the same one (hash of their name). */
const GRADIENTS = [
  'from-indigo-500 to-violet-500',
  'from-amber-400 to-orange-500',
  'from-emerald-400 to-teal-500',
  'from-pink-400 to-rose-500',
  'from-sky-400 to-blue-600',
  'from-fuchsia-500 to-purple-600',
];

/** Initials avatar on a colourful gradient, with an optional "online" dot. */
@Component({
  selector: 'pg-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'relative inline-block shrink-0' },
  template: `
    <span
      class="grid place-items-center rounded-full bg-linear-to-br font-display font-bold text-white ring-4 ring-white"
      [class]="gradient()"
      [style.width.px]="size()"
      [style.height.px]="size()"
      [style.font-size.px]="size() * 0.36"
      aria-hidden="true"
      >{{ initials() }}</span
    >
    @if (online()) {
      <span class="absolute bottom-0.5 right-0.5 size-3.5 rounded-full bg-emerald-500 ring-2 ring-white" title="Online now"></span>
    }
  `,
})
export class Avatar {
  readonly name = input.required<string>();
  readonly size = input(56);
  readonly online = input(false);

  protected readonly initials = computed(() =>
    this.name()
      .split(' ')
      .map((p) => p[0])
      .slice(0, 2)
      .join(''),
  );

  protected readonly gradient = computed(() => {
    let hash = 0;
    for (const ch of this.name()) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
    return GRADIENTS[hash % GRADIENTS.length];
  });
}
