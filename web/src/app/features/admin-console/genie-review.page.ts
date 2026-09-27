import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe, LowerCasePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  LucideArrowLeft as ArrowLeft,
  LucideCheck as Check,
  LucideDynamicIcon,
  LucideExternalLink as ExternalLink,
  LucideEye as Eye,
  LucideFileText as FileText,
  LucideFlag as Flag,
  LucideX as X,
} from '@lucide/angular';

import { API, errorMessage } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { AdminGenieDetail, Decision, GenieDocument, Wallet } from '../../core/api/models';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { DAY_NAMES, hhmm, humanize, inr, statusMeta } from '../../shared/format';
import { Avatar } from '../../shared/ui/avatar';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, Skeleton, Spinner } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';
import { AdminCounts } from './admin-console.page';

/** Which decisions make sense from each verification state (mirrors the API's state map). */
const DECISIONS: Record<string, Decision[]> = {
  REGISTERED: ['REJECT'],
  UNDER_REVIEW: ['APPROVE', 'NEEDS_CHANGES', 'REJECT'],
  NEEDS_CHANGES: ['REJECT'],
  APPROVED: ['SUSPEND'],
  SUSPENDED: ['REINSTATE'],
  REJECTED: [],
};

const DECISION_TEXT: Record<Decision, { label: string; tone: 'primary' | 'danger'; noteRequired: boolean; help: string }> = {
  APPROVE: { label: 'Approve', tone: 'primary', noteRequired: false, help: 'The Genie is listed and can take bookings.' },
  NEEDS_CHANGES: { label: 'Request changes', tone: 'primary', noteRequired: true, help: 'The Genie is told what to fix and can resubmit.' },
  REJECT: { label: 'Reject', tone: 'danger', noteRequired: true, help: 'The Genie cannot take bookings. They see your note.' },
  SUSPEND: { label: 'Suspend', tone: 'danger', noteRequired: true, help: 'Hidden from customers immediately; existing bookings stay.' },
  REINSTATE: { label: 'Reinstate', tone: 'primary', noteRequired: false, help: 'Listed again and can take bookings.' },
};

@Component({
  selector: 'pg-admin-genie-review-page',
  imports: [RouterLink, CurrencyPipe, DatePipe, DecimalPipe, LowerCasePipe, FormsModule, LucideDynamicIcon, Avatar, Dialog, EmptyState, Skeleton, Spinner, StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/admin/genies" class="inline-flex items-center gap-1 text-sm font-semibold text-muted hover:text-brand">
      <svg [lucideIcon]="ArrowLeft" [size]="16" aria-hidden="true"></svg> Genies
    </a>

    @if (detail.value(); as d) {
      @let p = d.profile;
      <div class="mt-3 flex flex-col gap-4 sm:flex-row sm:items-center">
        <pg-avatar [name]="p.fullName" [size]="64" [online]="p.online" />
        <div class="min-w-0 flex-1">
          <h1 class="flex flex-wrap items-center gap-2 text-2xl font-bold sm:text-3xl">{{ p.fullName }} <pg-status [status]="p.verificationStatus" /></h1>
          <p class="mt-1 text-sm text-muted">{{ p.phone }}{{ p.email ? ' · ' + p.email : '' }} · account {{ d.userStatus | lowercase }}</p>
        </div>
        @if (p.verificationStatus === 'APPROVED') {
          <a [routerLink]="['/genies', p.id]" target="_blank" class="btn-ghost"><svg [lucideIcon]="ExternalLink" [size]="16" aria-hidden="true"></svg> Public profile</a>
        }
      </div>

      @if (d.flaggedAt) {
        <div class="mt-5 flex flex-col gap-3 rounded-2xl bg-rose-50 p-4 ring-1 ring-rose-200 sm:flex-row sm:items-center">
          <svg [lucideIcon]="Flag" [size]="20" class="shrink-0 text-rose-600" aria-hidden="true"></svg>
          <p class="flex-1 text-sm text-rose-900"><span class="font-semibold">Flagged {{ d.flaggedAt | date: 'd MMM' }}:</span> {{ d.flagReason }}</p>
          <button type="button" class="btn-ghost min-h-10" (click)="clearFlag()">Clear flag</button>
        </div>
      }

      <div class="mt-6 grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div class="min-w-0 space-y-6">
          <!-- Documents -->
          <section class="card p-5" aria-labelledby="docs-h">
            <h2 id="docs-h" class="text-lg font-bold">Documents</h2>
            <ul class="mt-4 space-y-3">
              @for (doc of p.documents; track doc.id) {
                <li class="rounded-2xl border border-line p-4">
                  <div class="flex flex-col gap-3 sm:flex-row sm:items-center">
                    <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-mist text-brand-600"><svg [lucideIcon]="FileText" [size]="18" aria-hidden="true"></svg></span>
                    <div class="min-w-0 flex-1">
                      <p class="flex flex-wrap items-center gap-2 font-semibold">{{ humanize(doc.docType) }} <pg-status [status]="doc.status" /></p>
                      <p class="truncate text-xs text-muted">{{ doc.maskedNumber ? doc.maskedNumber + ' · ' : '' }}{{ doc.fileName }} · {{ kb(doc.sizeBytes) }} · {{ doc.createdAt | date: 'd MMM' }}</p>
                      @if (doc.reviewNote) {
                        <p class="mt-1 text-xs text-muted">Note: {{ doc.reviewNote }}</p>
                      }
                    </div>
                    <div class="flex flex-wrap gap-2">
                      <button type="button" class="btn-ghost min-h-10 px-4" (click)="view(doc)"><svg [lucideIcon]="Eye" [size]="16" aria-hidden="true"></svg> View</button>
                      @if (doc.status === 'PENDING') {
                        <button type="button" class="btn min-h-10 bg-emerald-600 px-4 text-white hover:bg-emerald-700" (click)="reviewDoc(doc, true)" [attr.aria-label]="'Approve ' + humanize(doc.docType)">
                          <svg [lucideIcon]="Check" [size]="16" aria-hidden="true"></svg>
                        </button>
                        <button type="button" class="btn min-h-10 border border-rose-200 bg-white px-4 text-rose-700 hover:bg-rose-50" (click)="reviewDoc(doc, false)" [attr.aria-label]="'Reject ' + humanize(doc.docType)">
                          <svg [lucideIcon]="X" [size]="16" aria-hidden="true"></svg>
                        </button>
                      }
                    </div>
                  </div>
                </li>
              } @empty {
                <li class="text-sm text-muted">No documents uploaded yet.</li>
              }
            </ul>
          </section>

          <!-- Profile -->
          <section class="card grid gap-5 p-5 sm:grid-cols-2" aria-label="Profile">
            <div class="sm:col-span-2">
              <p class="text-xs font-semibold uppercase tracking-wider text-muted">Bio</p>
              <p class="mt-1 text-sm">{{ p.bio || '—' }}</p>
            </div>
            <div><p class="text-xs font-semibold uppercase tracking-wider text-muted">Experience</p><p class="mt-1 font-semibold">{{ p.experienceYears ?? 0 }} years</p></div>
            <div><p class="text-xs font-semibold uppercase tracking-wider text-muted">Base</p><p class="mt-1 font-semibold">{{ p.baseArea ?? '—' }} · {{ p.serviceRadiusKm }} km radius</p></div>
            <div><p class="text-xs font-semibold uppercase tracking-wider text-muted">Payout UPI</p><p class="mt-1 font-semibold">{{ p.payoutUpiId ?? '—' }}</p></div>
            <div>
              <p class="text-xs font-semibold uppercase tracking-wider text-muted">Track record</p>
              <p class="mt-1 font-semibold">{{ p.completedJobs }} jobs · {{ p.ratingCount ? (p.avgRating | number: '1.1-1') + '★' : 'no ratings' }} · {{ p.cancellationCount }} cancellations</p>
            </div>
            <div class="sm:col-span-2">
              <p class="text-xs font-semibold uppercase tracking-wider text-muted">Services ({{ p.services.length }})</p>
              <ul class="mt-2 flex flex-wrap gap-2">
                @for (s of p.services; track s.serviceId) {
                  <li class="chip">{{ s.name }} · {{ s.price | currency: 'INR' : 'symbol' : '1.0-0' }}</li>
                } @empty {
                  <li class="text-sm text-muted">None yet</li>
                }
              </ul>
            </div>
            <div class="sm:col-span-2">
              <p class="text-xs font-semibold uppercase tracking-wider text-muted">Working hours</p>
              <ul class="mt-2 grid gap-1 text-sm sm:grid-cols-2">
                @for (w of p.availability; track $index) {
                  <li><span class="inline-block w-10 font-semibold">{{ dayNames[w.dayOfWeek - 1] }}</span> {{ hhmm(w.startTime) }}–{{ hhmm(w.endTime) }}</li>
                } @empty {
                  <li class="text-muted">Not set</li>
                }
              </ul>
            </div>
            @if (p.missingSteps.length) {
              <div class="sm:col-span-2">
                <p class="text-xs font-semibold uppercase tracking-wider text-muted">Still missing</p>
                <p class="mt-1 text-sm text-amber-900">{{ missing() }}</p>
              </div>
            }
          </section>

          <!-- History -->
          <section class="card p-5" aria-labelledby="vh-h">
            <h2 id="vh-h" class="text-lg font-bold">Verification history</h2>
            <ol class="mt-4 space-y-3 border-l-2 border-line pl-5">
              @for (h of d.history; track $index) {
                <li class="relative">
                  <span class="absolute -left-[1.72rem] top-1 size-3 rounded-full bg-brand-soft ring-4 ring-white" aria-hidden="true"></span>
                  <p class="text-sm font-semibold">{{ label(h.toStatus) }}</p>
                  <p class="text-xs text-muted">{{ h.createdAt | date: 'd MMM y, h:mm a' }}{{ h.actorName ? ' · ' + h.actorName : '' }}</p>
                  @if (h.reason && !isCode(h.reason)) {
                    <p class="mt-1 text-sm text-ink/75">“{{ h.reason }}”</p>
                  }
                </li>
              } @empty {
                <li class="text-sm text-muted">No changes yet.</li>
              }
            </ol>
          </section>
        </div>

        <aside class="space-y-6 xl:sticky xl:top-[calc(var(--header-h)+2.5rem)] xl:self-start">
          <section class="card p-5" aria-labelledby="dec-h">
            <h2 id="dec-h" class="text-lg font-bold">Decision</h2>
            @if (decisions().length) {
              <p class="mt-1 text-sm text-muted">
                {{ p.verificationStatus === 'UNDER_REVIEW' ? 'Check every document before approving.' : 'Current status: ' + label(p.verificationStatus) + '.' }}
              </p>
              <div class="mt-4 grid gap-2">
                @for (dec of decisions(); track dec) {
                  <button
                    type="button"
                    class="btn min-h-11"
                    [class]="dec === 'APPROVE' || dec === 'REINSTATE' ? 'bg-emerald-600 text-white hover:bg-emerald-700' : decisionText[dec].tone === 'danger' ? 'border border-rose-200 bg-white text-rose-700 hover:bg-rose-50' : 'btn-ghost'"
                    (click)="openDecision(dec)"
                  >
                    {{ decisionText[dec].label }}
                  </button>
                }
              </div>
              @if (p.verificationStatus === 'UNDER_REVIEW' && pendingDocs() > 0) {
                <p class="mt-3 text-xs text-amber-900">{{ pendingDocs() }} document(s) not reviewed yet.</p>
              }
            } @else {
              <p class="mt-1 text-sm text-muted">{{ p.verificationStatus === 'REJECTED' ? 'This Genie was rejected. No further decisions are possible.' : 'Waiting for the Genie to finish onboarding.' }}</p>
            }
          </section>

          @if (wallet.value(); as w) {
            <section class="card p-5" aria-labelledby="w-h">
              <h2 id="w-h" class="text-lg font-bold">Wallet</h2>
              <dl class="mt-3 space-y-2 text-sm">
                <div class="flex justify-between"><dt class="text-muted">Balance</dt><dd class="font-semibold" [class.text-rose-700]="w.balance < 0">{{ w.balance | currency: 'INR' }}</dd></div>
                <div class="flex justify-between"><dt class="text-muted">Commission due</dt><dd>{{ w.commissionDue | currency: 'INR' }}</dd></div>
                <div class="flex justify-between"><dt class="text-muted">Pending payouts</dt><dd>{{ w.pendingPayouts | currency: 'INR' }}</dd></div>
                <div class="flex justify-between"><dt class="text-muted">Lifetime earnings</dt><dd>{{ w.lifetimeEarnings | currency: 'INR' }}</dd></div>
              </dl>
              @if (w.commissionDue > 0) {
                <button type="button" class="btn-ghost mt-4 w-full" (click)="settleOpen.set(true)">Record commission payment</button>
              }
            </section>
          }
        </aside>
      </div>

      <!-- Decision dialog -->
      @let dec = pending();
      <pg-dialog [open]="!!dec" [heading]="dec ? decisionText[dec].label + ' ' + p.fullName.split(' ')[0] + '?' : ''" size="sm" (closed)="pending.set(null)">
        @if (dec) {
          <form (ngSubmit)="decide(dec)">
            <p class="text-sm text-muted">{{ decisionText[dec].help }}</p>
            <label class="label mt-4" for="dec-note">Note to the Genie {{ decisionText[dec].noteRequired ? '' : '(optional)' }}</label>
            <textarea id="dec-note" name="note" class="field min-h-24" maxlength="500" [(ngModel)]="note" [required]="decisionText[dec].noteRequired" placeholder="Be specific and kind."></textarea>
            <div class="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <button type="button" class="btn-ghost" (click)="pending.set(null)">Back</button>
              <button type="submit" class="btn text-white" [class]="decisionText[dec].tone === 'danger' ? 'bg-rose-600 hover:bg-rose-700' : 'bg-brand hover:bg-brand-600'" [disabled]="busy() || (decisionText[dec].noteRequired && !note.trim())">
                @if (busy()) {
                  <pg-spinner />
                }
                {{ decisionText[dec].label }}
              </button>
            </div>
          </form>
        }
      </pg-dialog>

      <pg-dialog [open]="settleOpen()" heading="Record a commission payment" subheading="Cash commission the Genie paid to ProGenie." size="sm" (closed)="settleOpen.set(false)">
        <form class="space-y-4" (ngSubmit)="settle()">
          <div>
            <label class="label" for="s-amt">Amount (₹)</label>
            <input id="s-amt" name="amount" type="number" min="1" class="field" [(ngModel)]="settleAmount" required />
          </div>
          <div>
            <label class="label" for="s-ref">UPI / bank reference</label>
            <input id="s-ref" name="reference" class="field" maxlength="100" [(ngModel)]="settleRef" required />
          </div>
          <div class="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" class="btn-ghost" (click)="settleOpen.set(false)">Cancel</button>
            <button type="submit" class="btn-primary" [disabled]="busy() || !settleRef.trim() || !settleAmount">Record payment</button>
          </div>
        </form>
      </pg-dialog>
    } @else if (detail.isLoading()) {
      <pg-skeleton class="mt-6 block" [rows]="3" />
    } @else if (detail.error()) {
      <pg-empty class="mt-6 block" title="Genie not found" />
    }
  `,
})
export default class AdminGenieReviewPage {
  readonly id = input.required<string>();

  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly counts = inject(AdminCounts);

  protected readonly detail = httpResource<AdminGenieDetail>(() => `${API}/admin/genies/${this.id()}`);
  protected readonly wallet = httpResource<Wallet>(() => `${API}/admin/genies/${this.id()}/wallet`);

  protected readonly pending = signal<Decision | null>(null);
  protected readonly busy = signal(false);
  protected readonly settleOpen = signal(false);
  protected note = '';
  protected settleAmount: number | null = null;
  protected settleRef = '';

  protected readonly decisionText = DECISION_TEXT;
  protected readonly dayNames = DAY_NAMES;
  protected readonly hhmm = hhmm;
  protected readonly humanize = humanize;

  protected readonly decisions = computed(() => DECISIONS[this.detail.value()?.profile.verificationStatus ?? ''] ?? []);
  protected readonly pendingDocs = computed(() => this.detail.value()?.profile.documents.filter((d) => d.status === 'PENDING').length ?? 0);
  protected readonly missing = computed(() => (this.detail.value()?.profile.missingSteps ?? []).map((s) => humanize(s)).join(', '));

  protected label(status: string): string {
    return statusMeta(status).label;
  }

  protected kb(bytes: number | null): string {
    if (!bytes) return '';
    return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }

  /** The API stores the decision name ("APPROVE") when no note was written; that is not worth quoting. */
  protected isCode(text: string): boolean {
    return /^[A-Z_]+$/.test(text);
  }

  protected openDecision(d: Decision): void {
    this.note = '';
    this.pending.set(d);
  }

  protected decide(decision: Decision): void {
    this.busy.set(true);
    this.api.decide(this.id(), decision, this.note.trim() || null).subscribe({
      next: (d) => {
        this.busy.set(false);
        this.pending.set(null);
        this.detail.set(d);
        this.counts.refresh();
        this.toast.success(`${DECISION_TEXT[decision].label}: done. The Genie has been notified.`);
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected async reviewDoc(doc: GenieDocument, approve: boolean): Promise<void> {
    let note: string | null = null;
    if (!approve) {
      const res = await this.confirm.ask({
        title: `Reject ${humanize(doc.docType)}?`,
        message: 'The Genie will be asked to upload it again.',
        confirmLabel: 'Reject document',
        tone: 'danger',
        input: { label: 'Reason', placeholder: 'e.g. Photo is blurry, number not readable', required: true },
      });
      if (!res.confirmed) return;
      note = res.value;
    }
    this.api.reviewDocument(doc.id, approve, note).subscribe({
      next: () => {
        this.toast.success(approve ? 'Document approved' : 'Document rejected');
        this.detail.reload();
      },
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }

  protected view(doc: GenieDocument): void {
    const tab = window.open('', '_blank');
    this.api.documentFile(doc.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        if (tab) tab.location.href = url;
        else window.location.assign(url);
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: (err) => {
        tab?.close();
        this.toast.error(errorMessage(err, 'Could not open the file.'));
      },
    });
  }

  protected async clearFlag(): Promise<void> {
    const { confirmed } = await this.confirm.ask({ title: 'Clear this flag?', message: 'Do this after you have spoken to the Genie.', confirmLabel: 'Clear flag' });
    if (!confirmed) return;
    this.api.clearFlag(this.id()).subscribe({
      next: () => {
        this.toast.success('Flag cleared');
        this.detail.reload();
      },
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }

  protected settle(): void {
    const amount = Number(this.settleAmount);
    if (!amount || !this.settleRef.trim()) return;
    this.busy.set(true);
    this.api.settle(this.id(), amount, this.settleRef.trim()).subscribe({
      next: (w) => {
        this.busy.set(false);
        this.settleOpen.set(false);
        this.settleAmount = null;
        this.settleRef = '';
        this.wallet.set(w);
        this.toast.success(`Recorded ${inr(amount)}`);
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected readonly ArrowLeft = ArrowLeft;
  protected readonly Check = Check;
  protected readonly ExternalLink = ExternalLink;
  protected readonly Eye = Eye;
  protected readonly FileText = FileText;
  protected readonly Flag = Flag;
  protected readonly X = X;
}
