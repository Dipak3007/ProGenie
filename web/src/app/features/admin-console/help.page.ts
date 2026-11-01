import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { API, errorMessage } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { HELP_CATEGORY_LABEL } from '../../core/api/help-api';
import { AdminHelpArticle, HelpArticleRequest, HelpCategory, TicketCategory } from '../../core/api/models';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { TICKET_CATEGORY_LABEL } from '../../shared/format';
import { Dialog } from '../../shared/ui/dialog';
import { PageHead, Skeleton, Spinner } from '../../shared/ui/kit';
import { Markdown } from '../../shared/ui/markdown';

type Form = HelpArticleRequest & { id: number | null };

/**
 * Help centre editor (Phase 3.5B): add, edit, hide and order the answers; tag them with complaint categories so
 * "Report a problem" suggests them; see how often people found each one helpful.
 */
@Component({
  selector: 'pg-admin-help-page',
  imports: [FormsModule, RouterLink, Dialog, Markdown, PageHead, Skeleton, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Help centre" subtitle="Answers customers, Genies and guests see at /help. Lower numbers come first in a category.">
      <div class="flex gap-2">
        <a routerLink="/help" target="_blank" class="btn-ghost">View</a>
        <button type="button" class="btn-primary" (click)="edit(null)">New answer</button>
      </div>
    </pg-page-head>

    @if (list.value(); as all) {
      @for (group of groups(); track group.category) {
        <section class="mt-6" [attr.aria-labelledby]="'ah-' + group.category">
          <h2 [id]="'ah-' + group.category" class="text-lg font-bold">{{ labels[group.category] }} <span class="text-sm font-normal text-muted">· {{ group.articles.length }}</span></h2>
          <ul class="mt-2 space-y-2">
            @for (a of group.articles; track a.id) {
              <li class="card flex flex-col gap-2 p-4 sm:flex-row sm:items-center" data-testid="admin-help-row">
                <div class="min-w-0 flex-1">
                  <p class="font-semibold">
                    <span class="mr-1 text-xs text-muted">{{ a.position }}</span> {{ a.question }}
                    @if (!a.published) {
                      <span class="chip ml-1 bg-surface text-muted">Hidden</span>
                    }
                  </p>
                  <p class="text-xs text-muted">
                    /help#{{ a.slug }}
                    @if (a.ticketCategories.length) {
                      · suggested for {{ tagLabels(a.ticketCategories) }}
                    }
                  </p>
                </div>
                <p class="text-sm text-muted sm:w-32 sm:text-right" [attr.aria-label]="a.helpfulYes + ' found it helpful, ' + a.helpfulNo + ' did not'">
                  👍 {{ a.helpfulYes }} · 👎 {{ a.helpfulNo }}
                </p>
                <div class="flex gap-2 sm:shrink-0">
                  <button type="button" class="btn-ghost min-h-10 px-4" (click)="edit(a)">Edit</button>
                  <button type="button" class="btn min-h-10 border border-rose-200 bg-white px-3 text-rose-700 hover:bg-rose-50" [attr.aria-label]="'Delete ' + a.question" (click)="remove(a)">
                    Delete
                  </button>
                </div>
              </li>
            }
          </ul>
        </section>
      }
    } @else if (list.isLoading()) {
      <pg-skeleton class="mt-6 block" [rows]="5" />
    }

    <pg-dialog [open]="formOpen()" [heading]="form.id ? 'Edit answer' : 'New answer'" size="lg" (closed)="formOpen.set(false)">
      @if (formOpen()) {
        <form class="space-y-4" (ngSubmit)="save()" #f="ngForm">
          <div class="grid gap-4 sm:grid-cols-[1fr_8rem]">
            <div>
              <label class="label" for="hq">Question</label>
              <input id="hq" name="question" class="field" required maxlength="200" [(ngModel)]="form.question" (ngModelChange)="suggestSlug()" />
            </div>
            <div>
              <label class="label" for="hpos">Order</label>
              <input id="hpos" name="position" type="number" class="field" min="0" max="10000" required [(ngModel)]="form.position" />
            </div>
          </div>
          <div class="grid gap-4 sm:grid-cols-2">
            <div>
              <label class="label" for="hcat">Category</label>
              <select id="hcat" name="category" class="field" [(ngModel)]="form.category">
                @for (c of categories; track c) {
                  <option [value]="c">{{ labels[c] }}</option>
                }
              </select>
            </div>
            <div>
              <label class="label" for="hslug">Link name</label>
              <input id="hslug" name="slug" class="field" required maxlength="80" pattern="[a-z0-9]+(-[a-z0-9]+)*" [(ngModel)]="form.slug" (input)="slugTouched = true" />
            </div>
          </div>
          <div class="grid gap-4 lg:grid-cols-2">
            <div>
              <label class="label" for="hans">Answer (Markdown)</label>
              <textarea id="hans" name="answer" class="field min-h-56 font-mono text-sm" required maxlength="10000" [(ngModel)]="form.answer"></textarea>
            </div>
            <div>
              <span class="label">Preview</span>
              <pg-markdown class="min-h-56 rounded-xl border border-line p-3 text-sm" [text]="form.answer" />
            </div>
          </div>
          <fieldset>
            <legend class="label">Suggest it in "Report a problem" for</legend>
            <div class="flex flex-wrap gap-2">
              @for (t of ticketCategories; track t) {
                <label class="flex items-center gap-2 rounded-full border border-line px-3 py-1.5 text-sm">
                  <input type="checkbox" class="accent-brand" [checked]="form.ticketCategories!.includes(t)" (change)="toggleTag(t)" />
                  {{ ticketLabels[t] }}
                </label>
              }
            </div>
          </fieldset>
          <label class="flex items-center gap-3 text-sm">
            <input type="checkbox" name="published" class="size-5 rounded accent-brand" [(ngModel)]="form.published" />
            Published (shown in the help centre)
          </label>
          @if (error()) {
            <p class="rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
          }
          <div class="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" class="btn-ghost" (click)="formOpen.set(false)">Cancel</button>
            <button type="submit" class="btn-primary" [disabled]="saving() || !f.valid">
              @if (saving()) {
                <pg-spinner />
              }
              Save answer
            </button>
          </div>
        </form>
      }
    </pg-dialog>
  `,
})
export default class AdminHelpPage {
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly list = httpResource<AdminHelpArticle[]>(() => `${API}/admin/help`);
  protected readonly labels = HELP_CATEGORY_LABEL;
  protected readonly categories = Object.keys(HELP_CATEGORY_LABEL) as HelpCategory[];
  protected readonly ticketLabels = TICKET_CATEGORY_LABEL;
  protected readonly ticketCategories: TicketCategory[] = ['QUALITY', 'NO_SHOW', 'DAMAGE', 'PAYMENT', 'BEHAVIOUR', 'SAFETY', 'PRIVACY', 'OTHER'];
  protected readonly formOpen = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected form: Form = blank();
  protected slugTouched = false;

  protected readonly groups = computed(() => {
    const all = this.list.value() ?? [];
    return this.categories.map((c) => ({ category: c, articles: all.filter((a) => a.category === c) })).filter((g) => g.articles.length);
  });

  protected tagLabels(tags: string[]): string {
    return tags.map((t) => (TICKET_CATEGORY_LABEL[t] ?? t).toLowerCase()).join(', ');
  }

  protected edit(a: AdminHelpArticle | null): void {
    this.form = a
      ? {
          id: a.id,
          category: a.category as HelpCategory,
          slug: a.slug,
          question: a.question,
          answer: a.answer,
          ticketCategories: [...(a.ticketCategories as TicketCategory[])],
          position: a.position,
          published: a.published,
        }
      : blank();
    this.slugTouched = !!a;
    this.error.set(null);
    this.formOpen.set(true);
  }

  /** A new answer's link name follows its question until the admin edits it. */
  protected suggestSlug(): void {
    if (this.slugTouched) return;
    this.form.slug = this.form.question
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 60)
      .replace(/-+$/, '');
  }

  protected toggleTag(t: TicketCategory): void {
    const tags = this.form.ticketCategories ?? [];
    this.form.ticketCategories = tags.includes(t) ? tags.filter((x) => x !== t) : [...tags, t];
  }

  protected save(): void {
    const { id, ...body } = this.form;
    this.saving.set(true);
    this.error.set(null);
    (id ? this.api.updateHelpArticle(id, body) : this.api.createHelpArticle(body)).subscribe({
      next: () => {
        this.saving.set(false);
        this.formOpen.set(false);
        this.toast.success('Answer saved');
        this.list.reload();
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }

  protected async remove(a: AdminHelpArticle): Promise<void> {
    const { confirmed } = await this.confirm.ask({
      title: 'Delete this answer?',
      message: `"${a.question}" disappears from the help centre. To keep it but not show it, untick Published instead.`,
      confirmLabel: 'Delete',
      tone: 'danger',
    });
    if (!confirmed) return;
    this.api.deleteHelpArticle(a.id).subscribe({
      next: () => {
        this.toast.success('Answer deleted');
        this.list.reload();
      },
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }
}

function blank(): Form {
  return { id: null, category: 'BOOKING', slug: '', question: '', answer: '', ticketCategories: [], position: 100, published: true };
}
