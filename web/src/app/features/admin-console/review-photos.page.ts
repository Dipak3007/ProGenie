import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { LucideCamera as ImageIcon } from '@lucide/angular';

import { API, errorMessage, query } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { AdminReviewPhoto, Page } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';

/**
 * Photos customers add to their reviews (Phase 3.5A). They are public on the Genie's profile; a Genie can report one,
 * and admins hide it (or keep it). Hidden photos stay visible here only.
 */
@Component({
  selector: 'pg-admin-review-photos-page',
  imports: [DatePipe, RouterLink, EmptyState, PageHead, Pager, Skeleton, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Review photos" subtitle="Photos customers post with their reviews. They're public on the Genie's profile until you hide one." />
    <pg-tabs class="mt-5" [items]="tabs" [value]="tab()" label="Which photos" (valueChange)="tab.set($event); page$.set(0)" />

    <div class="mt-5">
      @if (list.value(); as page) {
        <ul class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          @for (p of page.items; track p.id) {
            <li class="card flex flex-col overflow-hidden" data-testid="admin-review-photo">
              <a [href]="p.url" target="_blank" rel="noopener" class="block bg-surface">
                <img [src]="p.url" [alt]="'Review photo for ' + p.genieName" class="aspect-[4/3] w-full object-cover" [class.opacity-40]="p.hiddenAt" loading="lazy" />
              </a>
              <div class="flex flex-1 flex-col p-4">
                <p class="font-semibold">
                  <a [routerLink]="['/admin/genies', p.genieId]" class="hover:underline">{{ p.genieName }}</a>
                  <span class="text-gold" [attr.aria-label]="p.rating + ' out of 5'"> {{ '★'.repeat(p.rating) }}</span>
                </p>
                <p class="text-xs text-muted">By {{ p.customerName }} · {{ p.bookingRef }} · {{ p.createdAt | date: 'd MMM y' }}</p>
                @if (p.comment) {
                  <p class="mt-2 line-clamp-3 text-sm text-ink/80">{{ p.comment }}</p>
                }
                @if (p.hiddenAt) {
                  <p class="mt-2 rounded-lg bg-surface px-2 py-1 text-xs font-medium">Hidden {{ p.hiddenAt | date: 'd MMM, h:mm a' }}</p>
                } @else if (p.reportedAt) {
                  <p class="mt-2 rounded-lg bg-amber-50 px-2 py-1 text-xs font-medium text-amber-800">
                    Reported by the Genie {{ p.reportedAt | date: 'd MMM, h:mm a' }}{{ p.reportReason ? ': ' + p.reportReason : '' }}
                  </p>
                }
                <div class="mt-auto flex gap-2 pt-4">
                  @if (p.hiddenAt) {
                    <button type="button" class="btn-ghost min-h-10 flex-1" [disabled]="busyId() === p.id" (click)="keep(p)">Show again</button>
                  } @else {
                    @if (p.reportedAt) {
                      <button type="button" class="btn-ghost min-h-10 flex-1" [disabled]="busyId() === p.id" (click)="keep(p)">Keep</button>
                    }
                    <button
                      type="button"
                      class="btn min-h-10 flex-1 border border-rose-200 bg-white text-rose-700 hover:bg-rose-50"
                      [disabled]="busyId() === p.id"
                      (click)="hide(p)"
                    >
                      Hide
                    </button>
                  }
                </div>
              </div>
            </li>
          } @empty {
            <li class="sm:col-span-2 xl:col-span-3">
              <pg-empty
                [icon]="ImageIcon"
                [title]="tab() === 'REPORTED' ? 'No reported photos' : 'No review photos yet'"
                [message]="tab() === 'REPORTED' ? 'Photos Genies report land here.' : 'Photos customers post with reviews show up here.'"
              />
            </li>
          }
        </ul>
        <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="page$.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="3" />
      }
    </div>
  `,
})
export default class AdminReviewPhotosPage {
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);

  protected readonly tab = signal('REPORTED');
  protected readonly page$ = signal(0);
  protected readonly busyId = signal<string | null>(null);
  protected readonly tabs: TabItem[] = [
    { value: 'REPORTED', label: 'Reported' },
    { value: 'ALL', label: 'All photos' },
  ];
  protected readonly list = httpResource<Page<AdminReviewPhoto>>(
    () => `${API}/admin/review-photos${query({ reported: this.tab() === 'REPORTED', page: this.page$(), size: 24 })}`,
  );

  protected hide(p: AdminReviewPhoto): void {
    this.act(p, this.api.hideReviewPhoto(p.id), 'Photo hidden from the profile');
  }

  protected keep(p: AdminReviewPhoto): void {
    this.act(p, this.api.keepReviewPhoto(p.id), p.hiddenAt ? 'Photo shown again' : 'Photo kept');
  }

  private act(p: AdminReviewPhoto, call: ReturnType<AdminApi['hideReviewPhoto']>, done: string): void {
    this.busyId.set(p.id);
    call.subscribe({
      next: () => {
        this.busyId.set(null);
        this.toast.success(done);
        this.list.reload();
      },
      error: (err) => {
        this.busyId.set(null);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected readonly ImageIcon = ImageIcon;
}
