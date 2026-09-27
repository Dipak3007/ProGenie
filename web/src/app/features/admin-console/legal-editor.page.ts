import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { LucideArrowLeft as ArrowLeft, LucideDynamicIcon } from '@lucide/angular';

import { AdminApi } from '../../core/api/admin-api';
import { LEGAL_SLUG, LEGAL_TITLE } from '../../core/api/account-api';
import { API, errorMessage } from '../../core/api/api';
import { LegalDocument, LegalDraftRequest, LegalKind } from '../../core/api/models';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { Skeleton, Spinner, TabItem, Tabs } from '../../shared/ui/kit';
import { Markdown } from '../../shared/ui/markdown';

const BY_SLUG = Object.fromEntries(Object.entries(LEGAL_SLUG).map(([k, v]) => [v, k])) as Record<string, LegalKind>;

/**
 * Write the next version of a policy in Markdown with a live preview. Saving keeps one draft per policy; publishing
 * freezes it. "People must accept again" makes every signed-in user re-accept before they can book or pay.
 */
@Component({
  selector: 'pg-admin-legal-editor-page',
  imports: [DatePipe, FormsModule, RouterLink, LucideDynamicIcon, Markdown, Skeleton, Spinner, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/admin/legal" class="inline-flex items-center gap-1 text-sm font-semibold text-muted hover:text-brand">
      <svg [lucideIcon]="ArrowLeft" [size]="16" aria-hidden="true"></svg> Policies
    </a>

    @if (kindValue(); as k) {
      <h1 class="mt-3 text-2xl font-bold sm:text-3xl">{{ title[k] }}</h1>
      <p class="mt-1 text-sm text-muted">
        @if (current(); as c) {
          Version {{ c.version }} is in force (published {{ c.publishedAt | date: 'd MMM y' }}).
        } @else {
          Nothing is published yet.
        }
        @if (draft(); as d) {
          You are editing draft v{{ d.version }}, last saved {{ d.updatedAt | date: 'd MMM, h:mm a' }}.
        } @else {
          Saving creates draft v{{ (current()?.version ?? 0) + 1 }}.
        }
      </p>

      @if (docs.value()) {
        <form class="mt-6 space-y-5" (ngSubmit)="save()">
          <div class="grid gap-4 md:grid-cols-[minmax(0,1fr)_14rem]">
            <div>
              <label class="label" for="l-title">Title</label>
              <input id="l-title" name="title" class="field" maxlength="150" required [(ngModel)]="form.title" (ngModelChange)="touch()" />
            </div>
            <div>
              <label class="label" for="l-eff">Effective from (optional)</label>
              <input id="l-eff" name="eff" type="date" class="field" [(ngModel)]="form.effectiveFrom" (ngModelChange)="touch()" />
            </div>
          </div>

          <div class="card overflow-hidden">
            <div class="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-2">
              <pg-tabs class="lg:hidden" label="Editor view" [items]="views" [value]="view()" (valueChange)="view.set($event)" />
              <p class="hidden text-sm font-semibold lg:block">Markdown · preview</p>
              <p class="text-xs text-muted">Placeholders like [Company legal name] are filled on the public page.</p>
            </div>
            <div class="grid lg:grid-cols-2">
              <textarea
                name="body"
                class="min-h-[28rem] w-full resize-y border-0 bg-white p-4 font-mono text-sm focus:outline-none focus:ring-2 focus:ring-inset focus:ring-brand lg:border-r lg:border-line"
                [class.hidden]="view() === 'preview'"
                [class.lg:block]="true"
                aria-label="Policy text in Markdown"
                [(ngModel)]="form.body"
                (ngModelChange)="touch(); body.set($event)"
              ></textarea>
              <div class="max-h-[40rem] overflow-y-auto p-5" [class.hidden]="view() === 'write'" [class.lg:block]="true">
                <pg-markdown [text]="body()" />
              </div>
            </div>
          </div>

          <div>
            <label class="label" for="l-sum">What changed (shown to people)</label>
            <input id="l-sum" name="summary" class="field" maxlength="300" [(ngModel)]="form.changeSummary" (ngModelChange)="touch()" placeholder="e.g. Clarified refund timelines for cash bookings" />
          </div>
          @if (acceptedSeparately()) {
            <label class="flex items-start gap-3 text-sm">
              <input type="checkbox" name="re" class="mt-0.5 size-5 rounded accent-brand" [(ngModel)]="form.requiresReacceptance" (ngModelChange)="touch()" />
              <span>
                <span class="font-semibold">People must accept again.</span>
                <span class="text-muted">
                  Use for material changes. Signed-in {{ k === 'GENIE_AGREEMENT' ? 'Genies' : 'customers and Genies' }} see the new version and must accept it before
                  {{ k === 'GENIE_AGREEMENT' ? 'taking jobs' : 'booking or taking jobs' }}.
                </span>
              </span>
            </label>
          } @else {
            <p class="rounded-xl bg-surface px-4 py-3 text-sm text-muted">
              This policy is part of the Terms of Service, so people don't accept it on its own. For a material change, also publish a Terms update that
              asks people to accept again.
            </p>
          }

          <div class="flex flex-col gap-2 border-t border-line pt-5 sm:flex-row sm:flex-wrap">
            <button type="submit" class="btn-ghost" [disabled]="busy() || !valid()">
              @if (busy() === 'save') {
                <pg-spinner />
              }
              {{ dirty() ? 'Save draft' : 'Saved' }}
            </button>
            <button type="button" class="btn-primary" [disabled]="!!busy() || !valid()" (click)="publish()">
              @if (busy() === 'publish') {
                <pg-spinner />
              }
              Publish…
            </button>
            @if (draft()) {
              <button type="button" class="btn-ghost text-rose-700 sm:ml-auto" [disabled]="!!busy()" (click)="discard()">Delete draft</button>
            }
          </div>
        </form>

        @if (published().length) {
          <section class="mt-8">
            <h2 class="text-lg font-bold">Published versions</h2>
            <ul class="mt-3 divide-y divide-line rounded-2xl border border-line bg-white">
              @for (v of published(); track v.id) {
                <li class="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 text-sm">
                  <span class="font-semibold">v{{ v.version }}</span>
                  <span class="min-w-0 flex-1 text-muted">{{ v.changeSummary || v.title }}</span>
                  <span class="text-muted">{{ v.publishedAt | date: 'd MMM y' }}</span>
                  <button type="button" class="font-semibold text-brand hover:underline" (click)="copyFrom(v)">Start from this</button>
                </li>
              }
            </ul>
          </section>
        }
      } @else if (docs.isLoading()) {
        <pg-skeleton class="mt-6 block" [rows]="4" />
      }
    }
  `,
})
export default class AdminLegalEditorPage {
  readonly kind = input.required<string>();

  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly router = inject(Router);

  protected readonly title = LEGAL_TITLE;
  protected readonly views: TabItem[] = [
    { value: 'write', label: 'Write' },
    { value: 'preview', label: 'Preview' },
  ];
  protected readonly view = signal('write');
  protected readonly kindValue = computed<LegalKind | null>(() => BY_SLUG[this.kind()] ?? null);
  /** Terms, Privacy and the Genie agreement are accepted one by one; the refund policy is part of the Terms. */
  protected readonly acceptedSeparately = computed(() => this.kindValue() !== 'REFUNDS');
  protected readonly docs = httpResource<LegalDocument[]>(() => `${API}/admin/legal`);
  private readonly mine = computed(() => (this.docs.value() ?? []).filter((d) => d.kind === this.kindValue()));
  protected readonly published = computed(() => this.mine().filter((d) => d.publishedAt).sort((a, b) => b.version - a.version));
  protected readonly current = computed<LegalDocument | null>(() => this.published()[0] ?? null);
  protected readonly draft = computed<LegalDocument | null>(() => this.mine().find((d) => !d.publishedAt) ?? null);

  protected form: LegalDraftRequest = { title: '', body: '', changeSummary: '', requiresReacceptance: false, effectiveFrom: null };
  protected readonly body = signal('');
  protected readonly dirty = signal(false);
  protected readonly busy = signal<'save' | 'publish' | 'delete' | null>(null);
  private loadedFor: string | null = null;

  constructor() {
    effect(() => {
      const k = this.kind();
      if (!this.kindValue()) {
        untracked(() => this.router.navigateByUrl('/admin/legal'));
        return;
      }
      if (!this.docs.value() || this.loadedFor === k) return;
      this.loadedFor = k;
      untracked(() => {
        const src = this.draft() ?? this.current();
        this.fill(src, !this.draft());
        this.dirty.set(false);
      });
    });
  }

  protected valid(): boolean {
    return !!this.form.title.trim() && !!this.form.body.trim();
  }

  protected touch(): void {
    this.dirty.set(true);
  }

  protected copyFrom(doc: LegalDocument): void {
    this.fill(doc, true);
    this.dirty.set(true);
    this.toast.success(`Loaded v${doc.version}. Save to keep it as the draft.`);
  }

  private fill(doc: LegalDocument | null, fresh: boolean): void {
    this.form = {
      title: doc?.title ?? this.title[this.kindValue()!],
      body: doc?.body ?? '',
      changeSummary: fresh ? '' : (doc?.changeSummary ?? ''),
      requiresReacceptance: fresh ? false : !!doc?.requiresReacceptance,
      effectiveFrom: fresh ? null : (doc?.effectiveFrom ?? null),
    };
    this.body.set(this.form.body);
  }

  private request(): LegalDraftRequest {
    return {
      title: this.form.title.trim(),
      body: this.form.body,
      changeSummary: this.form.changeSummary?.trim() || null,
      requiresReacceptance: this.acceptedSeparately() && this.form.requiresReacceptance,
      effectiveFrom: this.form.effectiveFrom || null,
    };
  }

  protected save(then?: (doc: LegalDocument) => void): void {
    if (!this.valid()) return;
    this.busy.set(then ? 'publish' : 'save');
    this.api.saveLegalDraft(this.kindValue()!, this.request()).subscribe({
      next: (doc) => {
        this.dirty.set(false);
        this.docs.update((list) => [...(list ?? []).filter((d) => d.id !== doc.id), doc]);
        if (then) {
          then(doc);
        } else {
          this.busy.set(null);
          this.toast.success('Draft saved');
        }
      },
      error: (err) => this.fail(err),
    });
  }

  protected async publish(): Promise<void> {
    const { confirmed } = await this.confirm.ask({
      title: `Publish ${this.title[this.kindValue()!]}?`,
      message: this.acceptedSeparately() && this.form.requiresReacceptance
        ? 'It goes live now and cannot be edited. Every signed-in user will have to accept it again.'
        : 'It goes live now and cannot be edited afterwards.',
      confirmLabel: 'Publish',
    });
    if (!confirmed) return;
    this.save((doc) =>
      this.api.publishLegal(doc.id).subscribe({
        next: () => {
          this.busy.set(null);
          this.toast.success(`Version ${doc.version} is live`);
          this.router.navigateByUrl('/admin/legal');
        },
        error: (err) => this.fail(err),
      }),
    );
  }

  protected async discard(): Promise<void> {
    const { confirmed } = await this.confirm.ask({ title: 'Delete this draft?', message: 'The published version stays as it is.', confirmLabel: 'Delete draft', tone: 'danger' });
    if (!confirmed) return;
    this.busy.set('delete');
    this.api.deleteLegalDraft(this.kindValue()!).subscribe({
      next: () => {
        this.busy.set(null);
        this.docs.update((list) => (list ?? []).filter((d) => !(d.kind === this.kindValue() && !d.publishedAt)));
        this.fill(this.current(), true);
        this.dirty.set(false);
        this.toast.success('Draft deleted');
      },
      error: (err) => this.fail(err),
    });
  }

  private fail(err: unknown): void {
    this.busy.set(null);
    this.toast.error(errorMessage(err));
  }

  protected readonly ArrowLeft = ArrowLeft;
}
