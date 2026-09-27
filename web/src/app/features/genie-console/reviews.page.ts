import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LucideDynamicIcon, LucideMessageSquare as MessageSquare, LucideStar as Star } from '@lucide/angular';

import { API, errorMessage, query } from '../../core/api/api';
import { GenieApi } from '../../core/api/genie-api';
import { Page, Review } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { EmptyState, PageHead, Pager, Skeleton, Spinner } from '../../shared/ui/kit';
import { GenieStore } from './genie-store';

@Component({
  selector: 'pg-genie-reviews-page',
  imports: [DatePipe, DecimalPipe, FormsModule, LucideDynamicIcon, EmptyState, PageHead, Pager, Skeleton, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Reviews" subtitle="What customers say. A thoughtful reply (one per review) builds trust.">
      @if (store.profile(); as p) {
        <span class="inline-flex items-center gap-2 rounded-full bg-white px-4 py-2 text-sm font-semibold ring-1 ring-line">
          <svg [lucideIcon]="Star" [size]="16" class="fill-gold text-gold" aria-hidden="true"></svg>
          {{ p.ratingCount ? (p.avgRating | number: '1.1-1') : 'New' }} · {{ p.ratingCount }} reviews
        </span>
      }
    </pg-page-head>

    <div class="mt-6">
      @if (list.value(); as page) {
        <ul class="space-y-3">
          @for (r of page.items; track r.id) {
            <li class="card p-5">
              <div class="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <p class="font-semibold">{{ r.customerName }}</p>
                  <p class="text-xs text-muted">{{ r.serviceName }} · {{ r.bookingRef }} · {{ r.createdAt | date: 'd MMM y' }}</p>
                </div>
                <p class="text-gold" [attr.aria-label]="r.rating + ' out of 5'">{{ '★'.repeat(r.rating) }}<span class="text-line">{{ '★'.repeat(5 - r.rating) }}</span></p>
              </div>
              @if (r.comment) {
                <p class="mt-2 text-sm text-ink/80">{{ r.comment }}</p>
              }
              @if (r.genieReply) {
                <div class="mt-3 rounded-xl bg-surface px-4 py-3 text-sm">
                  <p class="text-xs font-semibold text-brand-600">Your reply · {{ r.repliedAt | date: 'd MMM' }}</p>
                  <p class="mt-1 text-ink/80">{{ r.genieReply }}</p>
                </div>
              } @else if (replyingTo() === r.id) {
                <form class="mt-3" (ngSubmit)="sendReply(r)">
                  <label class="sr-only" [for]="'reply-' + r.id">Your reply</label>
                  <textarea [id]="'reply-' + r.id" name="reply" class="field min-h-20" maxlength="600" [(ngModel)]="reply" placeholder="Thank the customer or respond politely to feedback."></textarea>
                  <div class="mt-2 flex justify-end gap-2">
                    <button type="button" class="btn-ghost" (click)="replyingTo.set(null)">Cancel</button>
                    <button type="submit" class="btn-primary" [disabled]="sending() || !reply.trim()">
                      @if (sending()) {
                        <pg-spinner />
                      }
                      Post reply
                    </button>
                  </div>
                </form>
              } @else {
                <button type="button" class="mt-3 inline-flex items-center gap-1.5 text-sm font-semibold text-brand hover:underline" (click)="startReply(r)">
                  <svg [lucideIcon]="MessageSquare" [size]="16" aria-hidden="true"></svg> Reply
                </button>
              }
            </li>
          } @empty {
            <pg-empty [icon]="Star" title="No reviews yet" message="After each completed job the customer can rate you. Great work gets noticed!" />
          }
        </ul>
        <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="page$.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="3" />
      }
    </div>
  `,
})
export default class GenieReviewsPage {
  protected readonly store = inject(GenieStore);
  private readonly api = inject(GenieApi);
  private readonly toast = inject(ToastService);

  protected readonly page$ = signal(0);
  protected readonly list = httpResource<Page<Review>>(() => `${API}/genie/reviews${query({ page: this.page$(), size: 10 })}`);
  protected readonly replyingTo = signal<string | null>(null);
  protected readonly sending = signal(false);
  protected reply = '';

  protected startReply(r: Review): void {
    this.reply = '';
    this.replyingTo.set(r.id ?? null);
  }

  protected sendReply(r: Review): void {
    if (!r.id) return;
    this.sending.set(true);
    this.api.reply(r.id, this.reply.trim()).subscribe({
      next: () => {
        this.sending.set(false);
        this.replyingTo.set(null);
        this.toast.success('Reply posted');
        this.list.reload();
      },
      error: (err) => {
        this.sending.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected readonly MessageSquare = MessageSquare;
  protected readonly Star = Star;
}
