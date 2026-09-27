import { ChangeDetectionStrategy, Component, Injectable, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  LucideCircleAlert as CircleAlert,
  LucideCircleCheck as CircleCheck,
  LucideDynamicIcon,
  LucideX as X,
} from '@lucide/angular';

import { Dialog } from '../../shared/ui/dialog';

// ------------------------------------------------------------------ toasts

export interface Toast {
  id: number;
  kind: 'success' | 'error' | 'info';
  text: string;
}

/** Short, non-blocking messages ("Booking cancelled"). */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private seq = 0;
  readonly toasts = signal<Toast[]>([]);

  success(text: string): void {
    this.push('success', text);
  }

  error(text: string): void {
    this.push('error', text, 7000);
  }

  info(text: string): void {
    this.push('info', text);
  }

  dismiss(id: number): void {
    this.toasts.update((list) => list.filter((t) => t.id !== id));
  }

  private push(kind: Toast['kind'], text: string, ms = 4000): void {
    const id = ++this.seq;
    this.toasts.update((list) => [...list.slice(-3), { id, kind, text }]);
    setTimeout(() => this.dismiss(id), ms);
  }
}

// ------------------------------------------------------------------ confirm

export interface ConfirmOptions {
  title: string;
  message?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: 'primary' | 'danger';
  /** Ask for a short text (e.g. a reason) together with the confirmation. */
  input?: { label: string; placeholder?: string; required?: boolean; multiline?: boolean; value?: string };
}

export interface ConfirmResult {
  confirmed: boolean;
  value: string;
}

/** Promise-based confirmation dialog: `const { confirmed, value } = await confirm.ask({...})`. */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  readonly current = signal<ConfirmOptions | null>(null);
  /** Text typed into the optional input. */
  readonly draft = signal('');
  private resolver: ((result: ConfirmResult) => void) | null = null;

  ask(options: ConfirmOptions): Promise<ConfirmResult> {
    this.resolver?.({ confirmed: false, value: '' });
    this.draft.set(options.input?.value ?? '');
    this.current.set(options);
    return new Promise((resolve) => (this.resolver = resolve));
  }

  resolve(result: ConfirmResult): void {
    this.current.set(null);
    this.resolver?.(result);
    this.resolver = null;
  }
}

/** Renders toasts and the confirm dialog once, in the app shell. */
@Component({
  selector: 'pg-feedback',
  imports: [FormsModule, Dialog, LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="pointer-events-none fixed inset-x-0 bottom-4 z-[60] flex flex-col items-center gap-2 px-4 sm:inset-x-auto sm:right-6 sm:bottom-6 sm:items-end"
      aria-live="polite"
    >
      @for (t of toasts.toasts(); track t.id) {
        <div
          class="pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-2xl px-4 py-3 text-sm font-medium shadow-xl ring-1"
          [class]="t.kind === 'error' ? 'bg-rose-50 text-rose-900 ring-rose-200' : t.kind === 'success' ? 'bg-navy text-white ring-white/10' : 'bg-white text-ink ring-line'"
          [attr.role]="t.kind === 'error' ? 'alert' : 'status'"
        >
          <svg [lucideIcon]="t.kind === 'error' ? CircleAlert : CircleCheck" [size]="18" class="mt-0.5 shrink-0" [class.text-gold]="t.kind === 'success'" aria-hidden="true"></svg>
          <p class="min-w-0 flex-1">{{ t.text }}</p>
          <button type="button" class="-mr-1 shrink-0 opacity-70 hover:opacity-100" aria-label="Dismiss" (click)="toasts.dismiss(t.id)">
            <svg [lucideIcon]="X" [size]="16" aria-hidden="true"></svg>
          </button>
        </div>
      }
    </div>

    @let c = confirm.current();
    <pg-dialog [open]="!!c" [heading]="c?.title ?? ''" size="sm" (closed)="cancel()">
      @if (c) {
        <form (ngSubmit)="ok(c)">
          @if (c.message) {
            <p class="text-sm text-ink/80">{{ c.message }}</p>
          }
          @if (c.input; as field) {
            <label class="label mt-4" for="confirm-input">{{ field.label }}</label>
            @if (field.multiline) {
              <textarea id="confirm-input" name="value" class="field min-h-24" [placeholder]="field.placeholder ?? ''" [ngModel]="confirm.draft()" (ngModelChange)="confirm.draft.set($event)" maxlength="500"></textarea>
            } @else {
              <input id="confirm-input" name="value" class="field" [placeholder]="field.placeholder ?? ''" [ngModel]="confirm.draft()" (ngModelChange)="confirm.draft.set($event)" maxlength="200" />
            }
          }
          <div class="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" class="btn-ghost" (click)="cancel()">{{ c.cancelLabel ?? 'Keep it' }}</button>
            <button
              type="submit"
              class="btn text-white"
              [class]="c.tone === 'danger' ? 'bg-rose-600 hover:bg-rose-700' : 'bg-brand hover:bg-brand-600'"
              [disabled]="!!c.input?.required && !confirm.draft().trim()"
            >
              {{ c.confirmLabel ?? 'Confirm' }}
            </button>
          </div>
        </form>
      }
    </pg-dialog>
  `,
})
export class Feedback {
  protected readonly toasts = inject(ToastService);
  protected readonly confirm = inject(ConfirmService);
  protected ok(c: ConfirmOptions): void {
    const value = this.confirm.draft().trim();
    if (c.input?.required && !value) return;
    this.confirm.resolve({ confirmed: true, value });
  }

  protected cancel(): void {
    if (this.confirm.current()) this.confirm.resolve({ confirmed: false, value: '' });
  }

  protected readonly CircleAlert = CircleAlert;
  protected readonly CircleCheck = CircleCheck;
  protected readonly X = X;
}
