import { ChangeDetectionStrategy, Component, ElementRef, effect, input, output, viewChild } from '@angular/core';
import { LucideDynamicIcon, LucideX as X } from '@lucide/angular';

/**
 * Modal built on the native <dialog> element (focus trap, Esc to close and inert background for free).
 * Phones get a bottom sheet; larger screens a centred card.
 */
@Component({
  selector: 'pg-dialog',
  imports: [LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog
      #dlg
      class="pg-dialog m-0 mt-auto max-h-[92dvh] w-full max-w-none overflow-y-auto rounded-t-3xl bg-white p-0 text-ink shadow-2xl backdrop:bg-navy/60 backdrop:backdrop-blur-sm sm:m-auto sm:rounded-3xl"
      [class.sm:max-w-md]="size() === 'sm'"
      [class.sm:max-w-lg]="size() === 'md'"
      [class.sm:max-w-2xl]="size() === 'lg'"
      [attr.aria-labelledby]="titleId"
      (close)="onNativeClose()"
      (click)="onBackdrop($event)"
    >
      <div class="p-5 sm:p-6">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <h2 [id]="titleId" class="text-xl font-bold">{{ heading() }}</h2>
            @if (subheading()) {
              <p class="mt-1 text-sm text-muted">{{ subheading() }}</p>
            }
          </div>
          <button type="button" class="-mr-2 -mt-1 grid size-10 shrink-0 place-items-center rounded-full text-muted hover:bg-surface hover:text-ink" aria-label="Close" (click)="dismiss()">
            <svg [lucideIcon]="X" [size]="20" aria-hidden="true"></svg>
          </button>
        </div>
        <div class="mt-4">
          <ng-content />
        </div>
      </div>
    </dialog>
  `,
})
export class Dialog {
  private static seq = 0;

  readonly open = input(false);
  readonly heading = input.required<string>();
  readonly subheading = input<string | null>(null);
  readonly size = input<'sm' | 'md' | 'lg'>('md');
  /** Emitted when the user closes it (X, Esc or backdrop click). The parent sets open=false. */
  readonly closed = output<void>();

  protected readonly titleId = `pg-dialog-${++Dialog.seq}`;
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');

  constructor() {
    effect(() => {
      const el = this.dialog().nativeElement;
      if (this.open() && !el.open) {
        el.showModal();
      } else if (!this.open() && el.open) {
        el.close();
      }
    });
  }

  protected dismiss(): void {
    this.closed.emit();
  }

  protected onNativeClose(): void {
    // Esc closes the native dialog directly; tell the parent so its state stays in sync.
    if (this.open()) this.closed.emit();
  }

  protected onBackdrop(event: MouseEvent): void {
    if (event.target === this.dialog().nativeElement) this.dismiss();
  }

  protected readonly X = X;
}
