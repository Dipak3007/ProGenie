import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import {
  LucideDynamicIcon,
  LucideIconInput,
  LucideMedal as Medal,
  LucideSparkles as Sparkles,
  LucideTrophy as Trophy,
  LucideZap as Zap,
} from '@lucide/angular';

/** What each badge means, in the words shown to customers and Genies (Phase 3A-7). */
export const BADGES: Record<string, { label: string; hint: string; classes: string; icon: LucideIconInput }> = {
  TOP_GENIE: { label: 'Top Genie', hint: 'Rated 4.8 or more by 20+ customers, and rarely cancels', classes: 'bg-amber-100 text-amber-900 ring-amber-300', icon: Trophy },
  QUICK_RESPONDER: { label: 'Quick responder', hint: 'Usually accepts requests within 10 minutes', classes: 'bg-sky-100 text-sky-900 ring-sky-300', icon: Zap },
  JOBS_500: { label: '500 jobs', hint: 'Has completed 500 or more jobs on ProGenie', classes: 'bg-violet-100 text-violet-900 ring-violet-300', icon: Medal },
  JOBS_100: { label: '100 jobs', hint: 'Has completed 100 or more jobs on ProGenie', classes: 'bg-violet-100 text-violet-900 ring-violet-300', icon: Medal },
  NEW: { label: 'New', hint: 'Joined ProGenie in the last 30 days', classes: 'bg-emerald-100 text-emerald-900 ring-emerald-300', icon: Sparkles },
};

/** A Genie's badges as small pills (on cards, profiles and the Genie's own dashboard). */
@Component({
  selector: 'pg-genie-badges',
  imports: [LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (items().length) {
      <ul class="flex flex-wrap gap-1.5" aria-label="Badges">
        @for (b of items(); track b.code) {
          <li
            class="inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset"
            [class]="b.classes"
            [title]="b.hint"
          >
            <svg [lucideIcon]="b.icon" [size]="12" aria-hidden="true"></svg>{{ b.label }}
            @if (explain()) {
              <span class="sr-only">: {{ b.hint }}</span>
            }
          </li>
        }
      </ul>
    }
  `,
})
export class GenieBadges {
  readonly badges = input<string[] | null | undefined>([]);
  /** Read the meaning out to screen readers too (profiles). */
  readonly explain = input(false);

  protected readonly items = computed(() =>
    (this.badges() ?? []).filter((code) => BADGES[code]).map((code) => ({ code, ...BADGES[code] })),
  );
}
