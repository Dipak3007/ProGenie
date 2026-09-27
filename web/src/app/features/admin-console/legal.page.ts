import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { RouterLink } from '@angular/router';

import { LEGAL_SLUG, LEGAL_TITLE } from '../../core/api/account-api';
import { API } from '../../core/api/api';
import { LegalDocument, LegalKind } from '../../core/api/models';
import { PageHead, Skeleton } from '../../shared/ui/kit';

const KINDS: LegalKind[] = ['TERMS', 'PRIVACY', 'REFUNDS', 'GENIE_AGREEMENT'];

/** The four policies: the version in force, any draft waiting, and a link to edit. */
@Component({
  selector: 'pg-admin-legal-page',
  imports: [DatePipe, RouterLink, PageHead, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Policies" subtitle="Draft, preview and publish the Terms, Privacy, Refund and Genie policies. Published versions never change." />

    @if (docs.value()) {
      <ul class="mt-6 grid gap-4 md:grid-cols-2">
        @for (row of rows(); track row.kind) {
          <li class="card flex flex-col p-5">
            <p class="text-xs font-semibold uppercase tracking-wider text-muted">{{ title[row.kind] }}</p>
            @if (row.current; as c) {
              <p class="mt-2 font-semibold">Version {{ c.version }} in force</p>
              <p class="text-sm text-muted">Published {{ c.publishedAt | date: 'd MMM y' }}{{ c.effectiveFrom ? ' · effective ' + (c.effectiveFrom | date: 'd MMM y') : '' }}</p>
            } @else {
              <p class="mt-2 font-semibold text-rose-700">Nothing published yet</p>
            }
            @if (row.draft; as d) {
              <p class="mt-3 rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900">
                Draft v{{ d.version }} saved {{ d.updatedAt | date: 'd MMM, h:mm a' }}{{ d.requiresReacceptance ? ' · people must accept again' : '' }}
              </p>
            }
            <p class="mt-2 text-xs text-muted">{{ row.count }} published version{{ row.count === 1 ? '' : 's' }}</p>
            <div class="mt-auto flex flex-wrap gap-2 pt-4">
              <a [routerLink]="['/admin/legal', slug[row.kind]]" class="btn-primary min-h-10 px-4">{{ row.draft ? 'Continue draft' : 'New version' }}</a>
              @if (row.current) {
                <a [routerLink]="['/legal', slug[row.kind]]" target="_blank" class="btn-ghost min-h-10 px-4">View live</a>
              }
            </div>
          </li>
        }
      </ul>
    } @else if (docs.isLoading()) {
      <pg-skeleton class="mt-6 block" [rows]="4" />
    }
  `,
})
export default class AdminLegalPage {
  protected readonly title = LEGAL_TITLE;
  protected readonly slug = LEGAL_SLUG;
  protected readonly docs = httpResource<LegalDocument[]>(() => `${API}/admin/legal`);

  protected readonly rows = computed(() => {
    const all = this.docs.value() ?? [];
    return KINDS.map((kind) => {
      const mine = all.filter((d) => d.kind === kind);
      const published = mine.filter((d) => d.publishedAt).sort((a, b) => b.version - a.version);
      return { kind, current: (published[0] ?? null) as LegalDocument | null, draft: mine.find((d) => !d.publishedAt) ?? null, count: published.length };
    });
  });
}
