import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * The final ProGenie logo: gold lamp in front of a crossed wrench and screwdriver, spark at the spout.
 * `tone="dark"` for dark backgrounds (lavender tools, white wordmark); `tone="light"` for light ones.
 */
@Component({
  selector: 'pg-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="inline-flex items-center gap-2">
      <svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 200 200" aria-hidden="true" [style.color]="toolColor()" style="transform: scale(1.4);">
        <g transform="rotate(45 100 100)" fill="currentColor">
          <rect x="95" y="44" width="10" height="104" rx="5" />
          <path d="M86 52 C83 38 90 28 96 26 L96 38 H104 L104 26 C110 28 117 38 114 52 Z" />
        </g>
        <g transform="rotate(-45 100 100)" fill="currentColor">
          <rect x="97" y="40" width="6" height="60" />
          <rect x="96" y="32" width="8" height="10" rx="2" />
          <rect x="91" y="98" width="18" height="50" rx="8" />
        </g>
        <path
          d="M58 150 C58 140 78 136 100 136 C122 136 142 140 142 150 C142 164 124 172 100 172 C84 172 72 168 64 162 C52 160 40 150 30 136 C42 140 52 144 58 150 Z"
          fill="#F5B301"
        />
        <path d="M140 146 C156 140 166 150 160 160 C156 167 148 168 142 165" fill="none" stroke="#F5B301" stroke-width="7" stroke-linecap="round" />
        <path d="M84 136 C84 126 116 126 116 136 Z" fill="#F5B301" />
        <circle cx="100" cy="122" r="4" fill="#F5B301" />
        <path d="M86 172 H114 L118 180 H82 Z" fill="#F5B301" />
        <path d="M28 100 Q28 110 38 110 Q28 110 28 120 Q28 110 18 110 Q28 110 28 100 Z" fill="#F5B301" />
      </svg>
      @if (showWordmark()) {
        <span class="font-display text-xl leading-none tracking-tight sm:text-2xl">
          <span class="font-medium" [class]="tone() === 'dark' ? 'text-brand-mist' : 'text-brand'">Pro</span>
          <span class="font-extrabold" [class]="tone() === 'dark' ? 'text-white' : 'text-ink'">Genie</span>
        </span>
      }
    </span>
  `,
})
export class Logo {
  readonly tone = input<'dark' | 'light'>('light');
  readonly size = input(40);
  readonly showWordmark = input(true);

  protected readonly toolColor = computed(() => (this.tone() === 'dark' ? '#A5B4FC' : '#4F46E5'));
}
