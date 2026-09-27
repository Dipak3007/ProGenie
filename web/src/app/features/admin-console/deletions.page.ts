import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { LucideUserX as UserX } from '@lucide/angular';

import { API, query } from '../../core/api/api';
import { DeletionRequestRow, Page } from '../../core/api/models';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';

/**
 * Account deletion requests (DPDP right to erasure). Accounts are anonymised automatically once the waiting period
 * ends; blocked ones (open bookings, money owed) are retried and show why here.
 */
@Component({
  selector: 'pg-admin-deletions-page',
  imports: [DatePipe, EmptyState, PageHead, Pager, Skeleton, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Account deletions" subtitle="People who asked to delete their account. Personal data is anonymised when the waiting period ends." />

    <pg-tabs class="mt-5 block" label="Filter deletion requests" [items]="tabs" [value]="tab()" (valueChange)="setTab($event)" />

    <div class="mt-5">
      @if (list.value(); as p) {
        @if (p.items.length) {
          <div class="card overflow-x-auto">
            <table class="w-full min-w-[40rem] text-left text-sm">
              <thead class="border-b border-line text-xs uppercase tracking-wider text-muted">
                <tr>
                  <th class="px-4 py-3 font-semibold">Person</th>
                  <th class="px-4 py-3 font-semibold">Status</th>
                  <th class="px-4 py-3 font-semibold">Requested</th>
                  <th class="px-4 py-3 font-semibold">Deletes on</th>
                  <th class="px-4 py-3 font-semibold">Notes</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-line">
                @for (r of p.items; track r.id) {
                  <tr>
                    <td class="px-4 py-3">
                      <p class="font-semibold">{{ r.fullName }}</p>
                      <p class="text-xs text-muted">{{ r.role === 'GENIE' ? 'Genie' : r.role === 'ADMIN' ? 'Admin' : 'Customer' }}</p>
                    </td>
                    <td class="px-4 py-3"><pg-status [status]="r.status" /></td>
                    <td class="px-4 py-3">{{ r.requestedAt | date: 'd MMM y' }}</td>
                    <td class="px-4 py-3">{{ (r.completedAt ?? r.scheduledFor) | date: 'd MMM y' }}</td>
                    <td class="max-w-72 px-4 py-3 text-muted">{{ r.note || r.reason || '—' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <pg-empty [icon]="UserX" title="No requests" message="Nothing matches this filter." />
        }
        <pg-pager [page]="p.page" [size]="p.size" [total]="p.total" (pageChange)="page.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="3" />
      }
    </div>
  `,
})
export default class AdminDeletionsPage {
  protected readonly tabs: TabItem[] = [
    { value: 'SCHEDULED', label: 'Scheduled' },
    { value: 'BLOCKED', label: 'Blocked' },
    { value: 'DONE', label: 'Done' },
    { value: 'CANCELLED', label: 'Cancelled' },
    { value: '', label: 'All' },
  ];
  protected readonly tab = signal('SCHEDULED');
  protected readonly page = signal(0);

  protected readonly list = httpResource<Page<DeletionRequestRow>>(
    () => `${API}/admin/deletion-requests${query({ status: this.tab() || null, page: this.page(), size: 20 })}`,
  );

  protected setTab(value: string): void {
    this.tab.set(value);
    this.page.set(0);
  }

  protected readonly UserX = UserX;
}
