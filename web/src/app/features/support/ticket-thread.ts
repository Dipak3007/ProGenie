import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  LucideCamera as Camera,
  LucideCheck as Check,
  LucideDynamicIcon,
  LucideLock as Lock,
  LucideRotateCcw as RotateCcw,
} from '@lucide/angular';

import { errorMessage } from '../../core/api/api';
import { TicketDetail, TicketMessage } from '../../core/api/models';
import { SupportApi } from '../../core/api/support-api';
import { AuthStore } from '../../core/auth/auth-store';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { TICKET_CATEGORY_LABEL } from '../../shared/format';
import { Avatar } from '../../shared/ui/avatar';
import { Spinner } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';

/**
 * A report's conversation: the original description, the replies, photos, and the resolution.
 * Customers and Genies reply, add photos, accept or reopen here; admins use the same view (with internal notes
 * highlighted) and get their own reply and resolution controls from the admin page.
 */
@Component({
  selector: 'pg-ticket-thread',
  imports: [DatePipe, FormsModule, LucideDynamicIcon, Avatar, Spinner, StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let t = ticket();
    <div class="card overflow-hidden">
      <div class="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line p-5">
        <pg-status [status]="t.status" [label]="statusLabel()" />
        @if (t.type === 'COMPLAINT') {
          <span class="chip">{{ categoryLabel[t.category] }}</span>
        }
        @if (t.priority !== 'NORMAL') {
          <pg-status [status]="t.priority" />
        }
        <span class="ml-auto text-xs text-muted">{{ t.ticketRef }} · opened {{ t.createdAt | date: 'd MMM, h:mm a' }}</span>
      </div>

      @if (t.status === 'RESOLVED' || t.status === 'REJECTED' || (t.status === 'CLOSED' && t.resolutionNote)) {
        <div class="border-b border-line p-5" [class]="t.status === 'REJECTED' ? 'bg-slate-50' : 'bg-emerald-50'">
          <p class="text-sm font-semibold">{{ t.status === 'REJECTED' ? 'Closed without action' : 'How we resolved it' }}</p>
          <p class="mt-1 text-sm">{{ t.resolutionNote }}</p>
          @if (t.redoBookingId) {
            <p class="mt-1 text-sm text-ink/75">A free redo was booked: {{ t.redoBookingRef }}.</p>
          }
          @if (t.canAccept || t.canReopen) {
            <div class="mt-3 flex flex-wrap gap-2">
              @if (t.canAccept) {
                <button type="button" class="btn-primary min-h-10 px-4" [disabled]="busy()" (click)="accept()">
                  <svg [lucideIcon]="Check" [size]="16" aria-hidden="true"></svg> I'm happy with this
                </button>
              }
              @if (t.canReopen) {
                <button type="button" class="btn-ghost min-h-10 px-4" [disabled]="busy()" (click)="reopen()">
                  <svg [lucideIcon]="RotateCcw" [size]="16" aria-hidden="true"></svg> Reopen
                </button>
              }
            </div>
          }
        </div>
      }

      <ol class="space-y-5 p-5">
        <li class="flex gap-3">
          <pg-avatar [name]="t.raisedByName" [size]="36" />
          <div class="min-w-0 flex-1">
            <p class="text-sm"><span class="font-semibold">{{ t.raisedByName }}</span> <span class="text-muted">· {{ roleLabel(t.raisedByRole) }} · {{ t.createdAt | date: 'd MMM, h:mm a' }}</span></p>
            <p class="mt-1 font-semibold">{{ t.subject }}</p>
            <p class="mt-1 whitespace-pre-line text-sm text-ink/85">{{ t.description }}</p>
          </div>
        </li>
        @for (m of t.messages; track m.id) {
          <li class="flex gap-3">
            @if (m.authorRole === 'SYSTEM') {
              <p class="w-full rounded-xl bg-surface px-4 py-2 text-center text-xs text-muted">{{ m.body }} · {{ m.createdAt | date: 'd MMM, h:mm a' }}</p>
            } @else {
              <pg-avatar [name]="m.authorName" [size]="36" />
              <div class="min-w-0 flex-1 rounded-2xl p-3" [class]="bubble(m)">
                <p class="flex flex-wrap items-center gap-x-1.5 text-sm">
                  <span class="font-semibold">{{ m.authorRole === 'ADMIN' ? 'ProGenie team' : m.authorName }}</span>
                  <span class="text-muted">· {{ m.createdAt | date: 'd MMM, h:mm a' }}</span>
                  @if (m.internal) {
                    <span class="inline-flex items-center gap-1 rounded-full bg-amber-200/70 px-2 text-xs font-semibold text-amber-900">
                      <svg [lucideIcon]="Lock" [size]="11" aria-hidden="true"></svg> Internal note
                    </span>
                  }
                </p>
                <p class="mt-1 whitespace-pre-line text-sm">{{ m.body }}</p>
              </div>
            }
          </li>
        }
      </ol>

      @if (t.attachments.length || t.canAddPhotos) {
        <div class="border-t border-line p-5">
          <p class="text-sm font-semibold">Photos</p>
          <div class="mt-3 flex flex-wrap gap-3">
            @for (a of t.attachments; track a.id) {
              @if (photoUrls()[a.id]; as url) {
                <a [href]="url" target="_blank" rel="noopener" class="block size-24 overflow-hidden rounded-2xl ring-1 ring-line">
                  <img [src]="url" [alt]="a.fileName ?? 'Photo'" class="size-full object-cover" />
                </a>
              } @else {
                <div class="grid size-24 place-items-center rounded-2xl bg-surface ring-1 ring-line"><pg-spinner /></div>
              }
            }
            @if (t.canAddPhotos) {
              <label class="grid size-24 cursor-pointer place-items-center rounded-2xl border-2 border-dashed border-line text-muted hover:border-brand hover:text-brand">
                <span class="flex flex-col items-center gap-1 text-xs font-semibold">
                  @if (uploading()) {
                    <pg-spinner />
                  } @else {
                    <svg [lucideIcon]="Camera" [size]="22" aria-hidden="true"></svg> Add photo
                  }
                </span>
                <input type="file" accept="image/jpeg,image/png,image/webp" class="sr-only" [disabled]="uploading()" (change)="upload($event)" />
              </label>
            }
          </div>
          @if (t.canAddPhotos) {
            <p class="mt-2 text-xs text-muted">Up to 5 photos, JPEG, PNG or WEBP, 5 MB each.</p>
          }
        </div>
      }

      @if (t.canReply && !adminMode()) {
        <form class="border-t border-line p-5" (ngSubmit)="reply()">
          <label class="label" for="reply">{{ t.status === 'AWAITING_REPLY' ? 'Our team is waiting for your answer' : 'Add a message' }}</label>
          <textarea id="reply" name="reply" class="field min-h-24" maxlength="2000" [(ngModel)]="draft" placeholder="Write your message"></textarea>
          <button type="submit" class="btn-primary mt-3" [disabled]="!draft.trim() || busy()">
            @if (busy()) {
              <pg-spinner />
            }
            Send
          </button>
        </form>
      }
    </div>
  `,
})
export class TicketThread {
  readonly ticket = input.required<TicketDetail>();
  /** Admins see internal notes and reply from the admin page. */
  readonly adminMode = input(false);
  readonly changed = output<TicketDetail>();

  private readonly api = inject(SupportApi);
  private readonly auth = inject(AuthStore);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly categoryLabel = TICKET_CATEGORY_LABEL;
  protected readonly busy = signal(false);
  protected readonly uploading = signal(false);
  protected readonly photoUrls = signal<Record<string, string>>({});
  protected draft = '';

  protected readonly statusLabel = computed(() => {
    const t = this.ticket();
    if (t.status === 'REJECTED') return 'Closed without action';
    if (t.status === 'AWAITING_REPLY') return this.adminMode() ? 'Awaiting reply' : 'Waiting for you';
    return null;
  });

  constructor() {
    // Photos are private: fetch each one with the login token and show it through an object URL.
    effect(() => {
      const t = this.ticket();
      untracked(() => {
        for (const a of t.attachments) {
          if (this.photoUrls()[a.id]) continue;
          this.api.photo(t.id, a.id).subscribe({
            next: (blob) => this.photoUrls.update((m) => ({ ...m, [a.id]: URL.createObjectURL(blob) })),
            error: () => undefined,
          });
        }
      });
    });
    this.destroyRef.onDestroy(() => Object.values(this.photoUrls()).forEach((u) => URL.revokeObjectURL(u)));
  }

  protected roleLabel(role: string): string {
    return { CUSTOMER: 'Customer', GENIE: 'Genie', ADMIN: 'ProGenie team' }[role] ?? role;
  }

  protected bubble(m: TicketMessage): string {
    if (m.internal) return 'bg-amber-50 ring-1 ring-amber-200';
    if (m.authorRole === 'ADMIN') return 'bg-brand-mist/60';
    return m.authorId === this.auth.user()?.id ? 'bg-surface' : 'bg-white ring-1 ring-line';
  }

  protected reply(): void {
    const text = this.draft.trim();
    if (!text) return;
    this.busy.set(true);
    this.api.reply(this.ticket().id, text).subscribe({
      next: (t) => {
        this.draft = '';
        this.done(t, 'Message sent');
      },
      error: (err) => this.fail(err),
    });
  }

  protected upload(event: Event): void {
    const el = event.target as HTMLInputElement;
    const file = el.files?.[0];
    el.value = '';
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) {
      this.toast.error('Photos can be at most 5 MB.');
      return;
    }
    this.uploading.set(true);
    this.api.addPhoto(this.ticket().id, file).subscribe({
      next: () => {
        this.uploading.set(false);
        this.api.get(this.ticket().id).subscribe({ next: (t) => this.changed.emit(t) });
        this.toast.success('Photo added');
      },
      error: (err) => {
        this.uploading.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected accept(): void {
    this.busy.set(true);
    this.api.accept(this.ticket().id).subscribe({ next: (t) => this.done(t, 'Thanks! The report is closed.'), error: (err) => this.fail(err) });
  }

  protected async reopen(): Promise<void> {
    const { confirmed, value } = await this.confirm.ask({
      title: 'Reopen this report?',
      message: 'Tell us what is still wrong. Our team will look at it again.',
      confirmLabel: 'Reopen',
      input: { label: 'What is still wrong?', required: true, multiline: true },
    });
    if (!confirmed) return;
    this.busy.set(true);
    this.api.reopen(this.ticket().id, value).subscribe({ next: (t) => this.done(t, 'Report reopened'), error: (err) => this.fail(err) });
  }

  private done(t: TicketDetail, message: string): void {
    this.busy.set(false);
    this.changed.emit(t);
    this.toast.success(message);
  }

  private fail(err: unknown): void {
    this.busy.set(false);
    this.toast.error(errorMessage(err));
  }

  protected readonly Camera = Camera;
  protected readonly Check = Check;
  protected readonly Lock = Lock;
  protected readonly RotateCcw = RotateCcw;
}
