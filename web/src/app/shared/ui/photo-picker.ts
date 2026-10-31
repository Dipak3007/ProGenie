import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, model } from '@angular/core';
import { LucideDynamicIcon, LucideImagePlus as ImagePlus, LucideX as X } from '@lucide/angular';

/**
 * Picks up to {@code max} photos before a form is sent (the Genie's optional before/after photos when starting or
 * finishing a job). Only previews here; the page uploads them after the action succeeds.
 */
@Component({
  selector: 'pg-photo-picker',
  imports: [LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-wrap gap-2">
      @for (f of previews(); track f.url; let i = $index) {
        <div class="relative size-20">
          <img [src]="f.url" [alt]="label() + ' ' + (i + 1)" class="size-full rounded-2xl object-cover ring-1 ring-line" />
          <button
            type="button"
            class="absolute -right-2 -top-2 grid size-7 place-items-center rounded-full bg-white text-ink shadow ring-1 ring-line hover:bg-surface"
            [attr.aria-label]="'Remove ' + label().toLowerCase() + ' ' + (i + 1)"
            (click)="removeAt(i)"
          >
            <svg [lucideIcon]="X" [size]="14" aria-hidden="true"></svg>
          </button>
        </div>
      }
      @if (files().length < max()) {
        <label class="grid size-20 cursor-pointer place-items-center rounded-2xl border-2 border-dashed border-line text-center text-[11px] font-semibold text-muted transition hover:border-brand hover:text-brand">
          <input type="file" accept="image/jpeg,image/png,image/webp" multiple class="sr-only" [attr.aria-label]="'Add ' + label().toLowerCase()" (change)="picked($event)" />
          <span class="flex flex-col items-center gap-1"><svg [lucideIcon]="ImagePlus" [size]="20" aria-hidden="true"></svg> Add</span>
        </label>
      }
    </div>
  `,
})
export class PhotoPicker {
  readonly files = model<File[]>([]);
  readonly max = input(3);
  readonly label = input('Photo');

  private readonly urls = new Map<File, string>();

  protected readonly previews = computed(() =>
    this.files().map((file) => {
      let url = this.urls.get(file);
      if (!url) {
        url = URL.createObjectURL(file);
        this.urls.set(file, url);
      }
      return { file, url };
    }),
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => this.urls.forEach((url) => URL.revokeObjectURL(url)));
  }

  protected picked(event: Event): void {
    const el = event.target as HTMLInputElement;
    const chosen = Array.from(el.files ?? []).filter((f) => f.type.startsWith('image/'));
    el.value = '';
    this.files.update((current) => [...current, ...chosen].slice(0, this.max()));
  }

  protected removeAt(i: number): void {
    const file = this.files()[i];
    const url = this.urls.get(file);
    if (url) {
      URL.revokeObjectURL(url);
      this.urls.delete(file);
    }
    this.files.update((current) => current.filter((_, j) => j !== i));
  }

  protected readonly ImagePlus = ImagePlus;
  protected readonly X = X;
}
