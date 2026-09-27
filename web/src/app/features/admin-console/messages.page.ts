import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { LucideDynamicIcon, LucideRefreshCw as RefreshCw, LucideSearch as SearchIcon, LucideSend as Send } from '@lucide/angular';

import { AdminApi } from '../../core/api/admin-api';
import { API, errorMessage, query } from '../../core/api/api';
import { OutboundMessage, Page } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';
import { AdminCounts } from './admin-console.page';

/** Outgoing SMS, WhatsApp and email: what went out, what failed, and a retry button. */
@Component({
  selector: 'pg-admin-messages-page',
  imports: [DatePipe, FormsModule, LucideDynamicIcon, EmptyState, PageHead, Pager, Skeleton, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Messages" subtitle="SMS, WhatsApp and email sent to people. Failed ones can be retried.">
      <button type="button" class="btn-ghost min-h-10 px-4" (click)="list.reload()">
        <svg [lucideIcon]="RefreshCw" [size]="16" aria-hidden="true"></svg> Refresh
      </button>
    </pg-page-head>

    <div class="mt-5 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
      <pg-tabs label="Filter messages" [items]="tabs" [value]="tab()" (valueChange)="setTab($event)" />
      <form class="flex gap-2" (ngSubmit)="search()">
        <div class="relative flex-1 lg:w-64">
          <svg [lucideIcon]="SearchIcon" [size]="16" class="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" aria-hidden="true"></svg>
          <input name="q" type="search" class="field py-2.5 pl-10" placeholder="Phone, email or template" [(ngModel)]="q" aria-label="Search messages" />
        </div>
        <select name="channel" class="field w-auto py-2.5" [(ngModel)]="channel" (ngModelChange)="search()" aria-label="Channel">
          <option value="">All channels</option>
          <option value="SMS">SMS</option>
          <option value="WHATSAPP">WhatsApp</option>
          <option value="EMAIL">Email</option>
        </select>
      </form>
    </div>

    <div class="mt-5">
      @if (list.value(); as p) {
        <ul class="space-y-3">
          @for (m of p.items; track m.id) {
            <li class="card p-4 sm:p-5">
              <div class="flex flex-wrap items-center gap-2">
                <pg-status [status]="m.status" />
                <span class="chip">{{ channelLabel[m.channel] }}</span>
                <span class="text-xs text-muted">{{ m.template }} · {{ m.createdAt | date: 'd MMM, h:mm:ss a' }}</span>
                @if (m.status === 'FAILED') {
                  <button type="button" class="btn-ghost ml-auto min-h-9 px-3 text-sm" [disabled]="retrying() === m.id" (click)="retry(m)">
                    <svg [lucideIcon]="Send" [size]="14" aria-hidden="true"></svg> Retry
                  </button>
                }
              </div>
              <p class="mt-2 break-all text-sm font-semibold">{{ m.destination }}</p>
              @if (m.subject) {
                <p class="text-sm">{{ m.subject }}</p>
              }
              <details class="mt-1 text-sm">
                <summary class="cursor-pointer text-muted">Message text</summary>
                <p class="mt-2 whitespace-pre-line rounded-xl bg-surface p-3 text-ink/85">{{ m.body }}</p>
              </details>
              <p class="mt-2 text-xs text-muted">
                {{ m.attempts }} attempt{{ m.attempts === 1 ? '' : 's' }}{{ m.provider ? ' · ' + m.provider : '' }}
                @if (m.sentAt) {
                  · sent {{ m.sentAt | date: 'd MMM, h:mm a' }}
                }
                @if (m.deliveryStatus) {
                  · {{ m.deliveryStatus.toLowerCase() }}
                }
              </p>
              @if (m.lastError) {
                <p class="mt-1 break-words text-xs text-rose-700">{{ m.lastError }}</p>
              }
            </li>
          } @empty {
            <pg-empty [icon]="Send" title="No messages" message="Nothing matches this filter." />
          }
        </ul>
        <pg-pager [page]="p.page" [size]="p.size" [total]="p.total" (pageChange)="page.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="4" />
      }
    </div>
  `,
})
export default class AdminMessagesPage {
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);
  private readonly counts = inject(AdminCounts);

  protected readonly channelLabel: Record<string, string> = { SMS: 'SMS', WHATSAPP: 'WhatsApp', EMAIL: 'Email' };
  protected readonly tabs: TabItem[] = [
    { value: 'FAILED', label: 'Failed' },
    { value: 'PENDING', label: 'Queued' },
    { value: 'SENT', label: 'Sent' },
    { value: 'SKIPPED', label: 'Skipped' },
    { value: '', label: 'All' },
  ];
  protected readonly tab = signal('FAILED');
  protected readonly page = signal(0);
  protected readonly retrying = signal<string | null>(null);
  protected q = '';
  protected channel = '';
  private readonly filters = signal({ q: '', channel: '' });

  protected readonly list = httpResource<Page<OutboundMessage>>(
    () =>
      `${API}/admin/messages${query({
        status: this.tab() || null,
        channel: this.filters().channel || null,
        q: this.filters().q || null,
        page: this.page(),
        size: 20,
      })}`,
  );

  protected setTab(value: string): void {
    this.tab.set(value);
    this.page.set(0);
  }

  protected search(): void {
    this.filters.set({ q: this.q.trim(), channel: this.channel });
    this.page.set(0);
  }

  protected retry(m: OutboundMessage): void {
    this.retrying.set(m.id);
    this.api.retryMessage(m.id).subscribe({
      next: () => {
        this.retrying.set(null);
        this.toast.success('Queued again');
        this.list.reload();
        this.counts.refresh();
      },
      error: (err) => {
        this.retrying.set(null);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected readonly RefreshCw = RefreshCw;
  protected readonly SearchIcon = SearchIcon;
  protected readonly Send = Send;
}
