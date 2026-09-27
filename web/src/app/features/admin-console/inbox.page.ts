import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { LucideDynamicIcon, LucideInbox as InboxIcon, LucideMail as Mail, LucidePhone as Phone } from '@lucide/angular';

import { API, errorMessage, query } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { ContactMessage, Page } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { telLink } from '../../shared/format';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';
import { AdminCounts } from './admin-console.page';

@Component({
  selector: 'pg-admin-inbox-page',
  imports: [DatePipe, LucideDynamicIcon, EmptyState, PageHead, Pager, Skeleton, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Inbox" subtitle="Messages from the Contact Us form." />
    <pg-tabs class="mt-5" [items]="tabs" [value]="status()" label="Message status" (valueChange)="status.set($event); page$.set(0)" />

    <div class="mt-5">
      @if (list.value(); as page) {
        <ul class="space-y-3">
          @for (m of page.items; track m.id) {
            <li class="card p-5">
              <div class="flex flex-wrap items-start justify-between gap-2">
                <div class="min-w-0">
                  <p class="flex flex-wrap items-center gap-2 font-semibold">{{ m.subject }} <pg-status [status]="m.status" /></p>
                  <p class="text-sm text-muted">{{ m.name }} · {{ m.createdAt | date: 'd MMM y, h:mm a' }}</p>
                </div>
              </div>
              <p class="mt-3 whitespace-pre-line text-sm text-ink/85">{{ m.message }}</p>
              <div class="mt-4 flex flex-wrap items-center gap-2 border-t border-line pt-4">
                <a [href]="mailto(m)" class="btn-ghost min-h-10 px-4"><svg [lucideIcon]="Mail" [size]="16" aria-hidden="true"></svg> Reply by email</a>
                @if (tel(m.phone); as t) {
                  <a [href]="t" class="btn-ghost min-h-10 px-4"><svg [lucideIcon]="Phone" [size]="16" aria-hidden="true"></svg> {{ m.phone }}</a>
                }
                <span class="flex-1"></span>
                @if (m.status === 'NEW') {
                  <button type="button" class="btn-ghost min-h-10 px-4" (click)="setStatus(m, 'IN_PROGRESS')">Start</button>
                }
                @if (m.status !== 'RESOLVED') {
                  <button type="button" class="btn-primary min-h-10 px-4" (click)="setStatus(m, 'RESOLVED')">Resolve</button>
                } @else {
                  <button type="button" class="btn-ghost min-h-10 px-4" (click)="setStatus(m, 'IN_PROGRESS')">Reopen</button>
                }
              </div>
            </li>
          } @empty {
            <pg-empty [icon]="InboxIcon" [title]="status() === 'NEW' ? 'Inbox zero' : 'No messages'" message="New Contact Us messages land here." />
          }
        </ul>
        <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="page$.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="3" />
      }
    </div>
  `,
})
export default class AdminInboxPage {
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);
  private readonly counts = inject(AdminCounts);

  protected readonly status = signal('NEW');
  protected readonly page$ = signal(0);
  protected readonly tabs: TabItem[] = [
    { value: 'NEW', label: 'New' },
    { value: 'IN_PROGRESS', label: 'In progress' },
    { value: 'RESOLVED', label: 'Resolved' },
    { value: 'ALL', label: 'All' },
  ];
  protected readonly list = httpResource<Page<ContactMessage>>(
    () => `${API}/admin/contact-messages${query({ status: this.status() === 'ALL' ? null : this.status(), page: this.page$(), size: 15 })}`,
  );
  protected readonly tel = telLink;

  protected mailto(m: ContactMessage): string {
    return `mailto:${m.email}?subject=${encodeURIComponent('Re: ' + m.subject)}`;
  }

  protected setStatus(m: ContactMessage, status: string): void {
    this.api.setMessageStatus(m.id, status).subscribe({
      next: () => {
        this.toast.success(status === 'RESOLVED' ? 'Marked as resolved' : 'Updated');
        this.list.reload();
        this.counts.refresh();
      },
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }

  protected readonly InboxIcon = InboxIcon;
  protected readonly Mail = Mail;
  protected readonly Phone = Phone;
}
