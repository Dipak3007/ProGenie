import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import {
  LucideDynamicIcon,
  LucideImagePlus as ImagePlus,
  LucideTrash2 as Trash2,
} from '@lucide/angular';

import { BookingPhoto } from '../../core/api/models';
import { Spinner } from './kit';

export type PhotoStage = 'BEFORE' | 'AFTER';

/**
 * Before/after job photos (Phase 3A-8) for the customer, the Genie and admins. Thumbnails open the full photo; the
 * links are short-lived, so an expired one asks the page to reload the booking instead. In edit mode (the Genie,
 * while the job allows it) each group has an "Add" tile and photos can be removed.
 */
@Component({
  selector: 'pg-job-photos',
  imports: [LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid gap-5 sm:grid-cols-2">
      @for (group of groups(); track group.stage) {
        <div>
          <p class="text-xs font-semibold uppercase tracking-wider text-muted">{{ group.label }}</p>
          <div class="mt-2 flex flex-wrap gap-2">
            @for (p of group.photos; track p.id; let i = $index) {
              <div class="relative">
                <a
                  [href]="p.url"
                  target="_blank"
                  rel="noopener"
                  class="block size-24 overflow-hidden rounded-2xl bg-surface ring-1 ring-line"
                  [attr.aria-label]="group.label + ' photo ' + (i + 1)"
                  (click)="open($event, p)"
                >
                  <img [src]="p.url" [alt]="group.label + ' photo ' + (i + 1)" class="size-full object-cover" loading="lazy" (error)="expired.emit()" />
                </a>
                @if (editable().includes(group.stage)) {
                  <button
                    type="button"
                    class="absolute -right-2 -top-2 grid size-8 place-items-center rounded-full bg-white text-rose-700 shadow ring-1 ring-line hover:bg-rose-50"
                    [attr.aria-label]="'Remove ' + group.label.toLowerCase() + ' photo ' + (i + 1)"
                    [disabled]="busy()"
                    (click)="remove.emit(p)"
                  >
                    <svg [lucideIcon]="Trash2" [size]="15" aria-hidden="true"></svg>
                  </button>
                }
              </div>
            }
            @if (addable().includes(group.stage)) {
              <label
                class="grid size-24 cursor-pointer place-items-center rounded-2xl border-2 border-dashed border-line text-center text-xs font-semibold text-muted transition hover:border-brand hover:text-brand"
                [class.pointer-events-none]="busy()"
              >
                <input type="file" accept="image/jpeg,image/png,image/webp" class="sr-only" [disabled]="busy()" (change)="picked($event, group.stage)" />
                @if (busy() === group.stage) {
                  <pg-spinner />
                } @else {
                  <span class="flex flex-col items-center gap-1"><svg [lucideIcon]="ImagePlus" [size]="22" aria-hidden="true"></svg> Add photo</span>
                }
              </label>
            }
            @if (!group.photos.length && !addable().includes(group.stage)) {
              <p class="text-sm text-muted">No photos</p>
            }
          </div>
        </div>
      }
    </div>
  `,
})
export class JobPhotos {
  readonly photos = input.required<BookingPhoto[]>();
  /** Stages the viewer can add to now (from the booking's ADD_PHOTO_* actions). */
  readonly addable = input<PhotoStage[]>([]);
  /** Stages whose photos the viewer can remove now. */
  readonly editable = input<PhotoStage[]>([]);
  /** The stage being uploaded, or false. */
  readonly busy = input<PhotoStage | false>(false);

  readonly add = output<{ stage: PhotoStage; file: File }>();
  readonly remove = output<BookingPhoto>();
  /** A link has expired: reload the booking for fresh ones. */
  readonly expired = output<void>();

  protected readonly groups = computed(() =>
    (['BEFORE', 'AFTER'] as PhotoStage[]).map((stage) => ({
      stage,
      label: stage === 'BEFORE' ? 'Before' : 'After',
      photos: this.photos().filter((p) => p.stage === stage),
    })),
  );

  protected open(event: MouseEvent, p: BookingPhoto): void {
    if (new Date(p.expiresAt).getTime() - Date.now() < 5_000) {
      event.preventDefault();
      this.expired.emit();
    }
  }

  protected picked(event: Event, stage: PhotoStage): void {
    const el = event.target as HTMLInputElement;
    const file = el.files?.[0];
    el.value = '';
    if (file) this.add.emit({ stage, file });
  }

  protected readonly ImagePlus = ImagePlus;
  protected readonly Trash2 = Trash2;
}

/** Stages a Genie may still change: BEFORE while in progress; AFTER also until 24 hours after completion. */
export function editableStages(status: string, completedAt: string | null | undefined): PhotoStage[] {
  if (status === 'IN_PROGRESS') return ['BEFORE', 'AFTER'];
  if (status === 'COMPLETED' && completedAt && Date.now() < new Date(completedAt).getTime() + 24 * 3600 * 1000) return ['AFTER'];
  return [];
}
