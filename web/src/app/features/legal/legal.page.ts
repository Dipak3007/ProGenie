import { ChangeDetectionStrategy, Component, computed, effect, inject, input, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { Title } from '@angular/platform-browser';
import { Router, RouterLink } from '@angular/router';
import { LucideDynamicIcon, LucidePrinter as Printer } from '@lucide/angular';

import { LEGAL_SLUG, LEGAL_TITLE } from '../../core/api/account-api';
import { API } from '../../core/api/api';
import { CompanyInfo, LegalDocument, LegalKind, LegalVersion } from '../../core/api/models';
import { EmptyState, Skeleton } from '../../shared/ui/kit';
import { Markdown } from '../../shared/ui/markdown';

const KINDS = Object.entries(LEGAL_SLUG) as [LegalKind, string][];

/** Public policy pages: /legal/terms, /legal/privacy, /legal/refunds, /legal/genie-agreement (?v= for an older version). */
@Component({
  selector: 'pg-legal-page',
  imports: [DatePipe, RouterLink, LucideDynamicIcon, EmptyState, Markdown, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container-page py-8 sm:py-12">
      <div class="grid grid-cols-[minmax(0,1fr)] gap-8 lg:grid-cols-[14rem_minmax(0,1fr)]">
        <nav aria-label="Policies" class="min-w-0 lg:sticky lg:top-[calc(var(--header-h)+3rem)] lg:self-start">
          <p class="hidden text-xs font-semibold uppercase tracking-widest text-muted lg:block">Policies</p>
          <ul class="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 lg:mx-0 lg:mt-3 lg:flex-col lg:overflow-visible lg:p-0">
            @for (k of kinds; track k[0]) {
              <li>
                <a
                  [routerLink]="['/legal', k[1]]"
                  class="block whitespace-nowrap rounded-full px-4 py-2 text-sm font-semibold transition lg:rounded-xl"
                  [class]="k[0] === kind() ? 'bg-navy text-white' : 'bg-white text-muted ring-1 ring-line hover:text-ink lg:bg-transparent lg:ring-0'"
                >
                  {{ titles[k[0]] }}
                </a>
              </li>
            }
          </ul>
        </nav>

        <article class="card min-w-0 p-5 sm:p-8 lg:p-10">
          @if (doc.value(); as d) {
            <header class="flex flex-col gap-3 border-b border-line pb-5 sm:flex-row sm:items-start sm:justify-between">
              <div class="min-w-0">
                <h1 class="text-3xl font-bold sm:text-4xl">{{ d.title }}</h1>
                <p class="mt-2 text-sm text-muted">
                  Version {{ d.version }}
                  @if (d.effectiveFrom) {
                    · effective {{ d.effectiveFrom | date: 'd MMMM yyyy' }}
                  }
                  @if (latest() && d.version !== latest()) {
                    · <span class="font-semibold text-amber-800">an older version</span>
                    · <a [routerLink]="['/legal', slug()]" class="font-semibold text-brand hover:underline">see the current one</a>
                  }
                </p>
              </div>
              <button type="button" class="btn-ghost min-h-10 shrink-0 px-4 print:hidden" (click)="print()">
                <svg [lucideIcon]="Printer" [size]="16" aria-hidden="true"></svg> Print
              </button>
            </header>
            <pg-markdown class="mt-6" [text]="d.body" />

            @if (kind() === 'PRIVACY' && company.value(); as c) {
              <section class="mt-8 rounded-2xl bg-surface p-5 text-sm" aria-labelledby="grievance-h">
                <h2 id="grievance-h" class="text-lg font-bold">Grievance officer</h2>
                <p class="mt-2">{{ c.grievanceName || 'To be announced' }}</p>
                @if (c.grievanceEmail) {
                  <p><a [href]="'mailto:' + c.grievanceEmail" class="font-semibold text-brand hover:underline">{{ c.grievanceEmail }}</a></p>
                }
                @if (c.address) {
                  <p class="text-muted">{{ c.address }}</p>
                }
                <p class="mt-2 text-muted">Logged in? You can also send a privacy request from Settings &amp; privacy.</p>
              </section>
            }

            @if ((versions.value() ?? []).length > 1) {
              <section class="mt-8 border-t border-line pt-5 text-sm" aria-labelledby="versions-h">
                <h2 id="versions-h" class="text-base font-bold">Earlier versions</h2>
                <ul class="mt-2 space-y-1">
                  @for (v of versions.value(); track v.version) {
                    <li>
                      <a [routerLink]="['/legal', slug()]" [queryParams]="{ v: v.version }" class="text-brand hover:underline">Version {{ v.version }}</a>
                      <span class="text-muted"> · {{ v.publishedAt | date: 'd MMM yyyy' }}{{ v.changeSummary ? ' · ' + v.changeSummary : '' }}</span>
                    </li>
                  }
                </ul>
              </section>
            }
          } @else if (doc.isLoading()) {
            <pg-skeleton [rows]="4" />
          } @else {
            <pg-empty title="This page isn't available" message="It may not be published yet.">
              <a routerLink="/" class="btn-ghost">Go home</a>
            </pg-empty>
          }
        </article>
      </div>
    </div>
  `,
})
export default class LegalPage {
  /** URL slug, e.g. "terms". */
  readonly kindSlug = input.required<string>({ alias: 'kind' });
  /** ?v=2 shows an older version. */
  readonly v = input<string>();

  private readonly title = inject(Title);
  private readonly router = inject(Router);
  protected readonly kinds = KINDS;
  protected readonly titles = LEGAL_TITLE;

  protected readonly kind = computed<LegalKind | null>(() => KINDS.find(([, s]) => s === this.kindSlug())?.[0] ?? null);
  protected readonly slug = computed(() => this.kindSlug());

  protected readonly doc = httpResource<LegalDocument>(() => {
    if (!this.kind()) return undefined;
    const version = Number(this.v());
    return version > 0 ? `${API}/legal/${this.slug()}/versions/${version}` : `${API}/legal/${this.slug()}`;
  });
  protected readonly versions = httpResource<LegalVersion[]>(() => (this.kind() ? `${API}/legal/${this.slug()}/versions` : undefined));
  protected readonly company = httpResource<CompanyInfo>(() => (this.kind() === 'PRIVACY' ? `${API}/legal/company` : undefined));
  protected readonly latest = computed(() => this.versions.value()?.[0]?.version ?? null);

  constructor() {
    effect(() => {
      const k = this.kind();
      if (!k) {
        untracked(() => this.router.navigateByUrl('/legal/terms'));
        return;
      }
      this.title.setTitle(`${LEGAL_TITLE[k]} · ProGenie`);
    });
  }

  protected print(): void {
    window.print();
  }

  protected readonly Printer = Printer;
}
