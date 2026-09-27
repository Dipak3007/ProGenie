import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  LucideBell as Bell,
  LucideDownload as Download,
  LucideDynamicIcon,
  LucideFileText as FileText,
  LucideLifeBuoy as LifeBuoy,
  LucideTrash2 as Trash,
} from '@lucide/angular';

import { AccountApi, LEGAL_SLUG, LEGAL_TITLE } from '../../core/api/account-api';
import { errorCode, errorMessage } from '../../core/api/api';
import { Consent, DeletionStatus, ExportJob, MessageCategory, NotificationPreference } from '../../core/api/models';
import { SupportApi } from '../../core/api/support-api';
import { AuthStore } from '../../core/auth/auth-store';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { saveBlob } from '../../shared/format';
import { PageHead, Skeleton, Spinner } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';

const CATEGORY_TEXT: Record<MessageCategory, { title: string; hint: string }> = {
  BOOKING: { title: 'Bookings', hint: 'Confirmations, reminders, cancellations' },
  PAYMENT: { title: 'Payments', hint: 'Receipts, refunds, payouts' },
  SUPPORT: { title: 'Reported problems', hint: 'Replies and resolutions' },
  ACCOUNT: { title: 'Account', hint: 'Verification decisions and account notices' },
  PROMOTIONS: { title: 'Offers and tips', hint: 'Only if you want them' },
};

/**
 * Settings & privacy for every role: message preferences, accepted policies, and (customers and Genies)
 * data export, privacy requests and account deletion.
 */
@Component({
  selector: 'pg-settings-page',
  imports: [DatePipe, DecimalPipe, FormsModule, RouterLink, LucideDynamicIcon, PageHead, Skeleton, Spinner, StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Settings & privacy" subtitle="How we contact you, and what we keep about you." />

    <div class="mt-6 grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-2">
      <!-- Notifications -->
      <section class="card p-5 sm:p-6 xl:col-span-2" aria-labelledby="notif-h">
        <div class="flex items-center gap-3">
          <span class="grid size-10 place-items-center rounded-2xl bg-brand-mist text-brand-600"><svg [lucideIcon]="Bell" [size]="20" aria-hidden="true"></svg></span>
          <div>
            <h2 id="notif-h" class="text-lg font-bold">Messages outside the app</h2>
            <p class="text-sm text-muted">In-app notifications are always on. Codes, security notices{{ auth.role() === 'GENIE' ? ' and new booking requests' : '' }} are always sent.</p>
          </div>
        </div>
        @if (prefs().length) {
          <div class="mt-5 overflow-x-auto">
            <table class="w-full min-w-[17rem] text-sm">
              <thead>
                <tr class="text-left text-[11px] uppercase text-muted sm:text-xs sm:tracking-wider">
                  <th class="py-2 pr-2 font-semibold sm:pr-4">About</th>
                  <th class="w-14 py-2 text-center font-semibold sm:w-20">SMS</th>
                  <th class="w-14 py-2 text-center font-semibold sm:w-20">WhatsApp</th>
                  <th class="w-14 py-2 text-center font-semibold sm:w-20">Email</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-line">
                @for (p of prefs(); track p.category) {
                  <tr>
                    <td class="py-3 pr-2 sm:pr-4">
                      <p class="font-semibold">{{ text[p.category].title }}</p>
                      <p class="text-xs text-muted">{{ text[p.category].hint }}</p>
                    </td>
                    @for (ch of channels; track ch) {
                      <td class="py-3 text-center">
                        <input
                          type="checkbox"
                          class="size-5 accent-brand"
                          [attr.aria-label]="text[p.category].title + ' by ' + ch"
                          [checked]="p[ch]"
                          (change)="toggle(p.category, ch, $any($event.target).checked)"
                        />
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="mt-4 flex items-center justify-between gap-3">
            <p class="text-xs text-muted">{{ dirty() ? 'You have unsaved changes.' : '' }}</p>
            <button type="button" class="btn-primary" [disabled]="!dirty() || busy()" (click)="savePrefs()">Save preferences</button>
          </div>
        } @else {
          <pg-skeleton class="mt-4 block" [rows]="2" />
        }
      </section>

      <!-- Policies -->
      <section class="card p-5 sm:p-6" aria-labelledby="policies-h">
        <div class="flex items-center gap-3">
          <span class="grid size-10 place-items-center rounded-2xl bg-brand-mist text-brand-600"><svg [lucideIcon]="FileText" [size]="20" aria-hidden="true"></svg></span>
          <h2 id="policies-h" class="text-lg font-bold">Policies you accepted</h2>
        </div>
        <ul class="mt-4 divide-y divide-line text-sm">
          @for (c of consents(); track c.kind + c.version) {
            <li class="flex items-center justify-between gap-3 py-2.5">
              <span>
                <a [routerLink]="['/legal', slug[c.kind]]" [queryParams]="{ v: c.version }" class="font-semibold text-brand hover:underline">{{ c.title }}</a>
                <span class="text-muted"> · version {{ c.version }}</span>
              </span>
              <span class="text-xs text-muted">{{ c.acceptedAt | date: 'd MMM yyyy' }}</span>
            </li>
          } @empty {
            <li class="py-2.5 text-muted">Nothing recorded yet.</li>
          }
        </ul>
        <p class="mt-3 text-xs text-muted">
          Read the current <a routerLink="/legal/terms" class="text-brand hover:underline">Terms</a>,
          <a routerLink="/legal/privacy" class="text-brand hover:underline">Privacy Policy</a> and
          <a routerLink="/legal/refunds" class="text-brand hover:underline">Refund Policy</a>.
        </p>
      </section>

      @if (auth.role() !== 'ADMIN') {
        <!-- Data export -->
        <section class="card p-5 sm:p-6" aria-labelledby="export-h">
          <div class="flex items-center gap-3">
            <span class="grid size-10 place-items-center rounded-2xl bg-brand-mist text-brand-600"><svg [lucideIcon]="Download" [size]="20" aria-hidden="true"></svg></span>
            <div>
              <h2 id="export-h" class="text-lg font-bold">Download my data</h2>
              <p class="text-sm text-muted">A ZIP of everything we hold about you. Ready in a minute, available for 7 days.</p>
            </div>
          </div>
          <ul class="mt-4 space-y-2 text-sm">
            @for (j of exports(); track j.id) {
              <li class="flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-line px-4 py-3">
                <span>
                  <span class="font-semibold">Requested {{ j.requestedAt | date: 'd MMM, h:mm a' }}</span>
                  @if (j.status === 'READY' && j.expiresAt) {
                    <span class="block text-xs text-muted">{{ (j.sizeBytes ?? 0) / 1024 | number: '1.0-0' }} KB · until {{ j.expiresAt | date: 'd MMM' }}</span>
                  }
                </span>
                @if (j.status === 'READY') {
                  <button type="button" class="btn-ghost min-h-9 px-4" (click)="download(j)">
                    <svg [lucideIcon]="Download" [size]="16" aria-hidden="true"></svg> Download
                  </button>
                } @else {
                  <pg-status [status]="j.status" [label]="j.status === 'PENDING' ? 'Preparing…' : null" />
                }
              </li>
            }
          </ul>
          <button type="button" class="btn-primary mt-4" [disabled]="busy() || hasPending()" (click)="requestExport()">
            @if (hasPending()) {
              <pg-spinner /> Preparing your data…
            } @else {
              Request a copy
            }
          </button>
        </section>

        <!-- Privacy request -->
        <section class="card p-5 sm:p-6" aria-labelledby="privreq-h">
          <div class="flex items-center gap-3">
            <span class="grid size-10 place-items-center rounded-2xl bg-brand-mist text-brand-600"><svg [lucideIcon]="LifeBuoy" [size]="20" aria-hidden="true"></svg></span>
            <div>
              <h2 id="privreq-h" class="text-lg font-bold">Privacy request</h2>
              <p class="text-sm text-muted">Correct your data, withdraw consent or ask the grievance officer anything.</p>
            </div>
          </div>
          <form class="mt-4 space-y-3" (ngSubmit)="sendPrivacyRequest()" #pr="ngForm">
            <div>
              <label class="label" for="pr-subject">Subject</label>
              <input id="pr-subject" name="subject" class="field" required maxlength="150" [(ngModel)]="prSubject" placeholder="e.g. Please correct my name" />
            </div>
            <div>
              <label class="label" for="pr-body">Details</label>
              <textarea id="pr-body" name="body" class="field min-h-24" required maxlength="2000" [(ngModel)]="prBody"></textarea>
            </div>
            <button type="submit" class="btn-ghost" [disabled]="!pr.valid || busy()">Send request</button>
          </form>
        </section>

        <!-- Delete account -->
        <section class="card border-rose-200 p-5 sm:p-6 xl:col-span-2" aria-labelledby="delete-h">
          <div class="flex items-center gap-3">
            <span class="grid size-10 place-items-center rounded-2xl bg-rose-50 text-rose-700"><svg [lucideIcon]="Trash" [size]="20" aria-hidden="true"></svg></span>
            <div>
              <h2 id="delete-h" class="text-lg font-bold">Delete my account</h2>
              <p class="text-sm text-muted">
                Your name, contact details, addresses and preferences are removed after a 7-day grace period. Bookings, payments and
                receipts are kept for tax law, without your personal details.
              </p>
            </div>
          </div>
          @if (deletion(); as d) {
            @if (d.status === 'SCHEDULED') {
              <div class="mt-4 flex flex-col gap-3 rounded-2xl bg-rose-50 p-4 text-sm text-rose-900 sm:flex-row sm:items-center">
                <p class="flex-1">Deletion scheduled for <span class="font-semibold">{{ d.scheduledFor | date: 'd MMMM yyyy' }}</span>. Changed your mind?</p>
                <button type="button" class="btn border border-rose-300 bg-white text-rose-800 hover:bg-rose-100" [disabled]="busy()" (click)="cancelDeletion()">Keep my account</button>
              </div>
            } @else if (d.blockers.length) {
              <div class="mt-4 rounded-2xl bg-amber-50 p-4 text-sm text-amber-900">
                <p class="font-semibold">Please settle these first:</p>
                <ul class="mt-2 list-disc space-y-1 pl-5">
                  @for (b of d.blockers; track b.code) {
                    <li>{{ capitalise(b.message) }}</li>
                  }
                </ul>
              </div>
            } @else {
              <button type="button" class="btn mt-4 border border-rose-200 bg-white text-rose-700 hover:bg-rose-50" [disabled]="busy()" (click)="requestDeletion()">
                Delete my account…
              </button>
            }
          } @else {
            <pg-skeleton class="mt-4 block" [rows]="1" />
          }
        </section>
      }
    </div>
  `,
})
export default class SettingsPage implements OnInit {
  protected readonly auth = inject(AuthStore);
  private readonly api = inject(AccountApi);
  private readonly support = inject(SupportApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly text = CATEGORY_TEXT;
  protected readonly slug = LEGAL_SLUG;
  protected readonly titles = LEGAL_TITLE;
  protected readonly channels = ['sms', 'whatsapp', 'email'] as const;

  protected readonly prefs = signal<NotificationPreference[]>([]);
  private readonly savedPrefs = signal<string>('');
  protected readonly dirty = computed(() => JSON.stringify(this.prefs()) !== this.savedPrefs());
  protected readonly consents = signal<Consent[]>([]);
  protected readonly exports = signal<ExportJob[]>([]);
  protected readonly hasPending = computed(() => this.exports().some((j) => j.status === 'PENDING'));
  protected readonly deletion = signal<DeletionStatus | null>(null);
  protected readonly busy = signal(false);
  protected prSubject = '';
  protected prBody = '';
  private poll: ReturnType<typeof setInterval> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.stopPolling());
  }

  ngOnInit(): void {
    this.api.preferences().subscribe({ next: (p) => this.setPrefs(p), error: (err) => this.toast.error(errorMessage(err)) });
    this.api.consents().subscribe({ next: (c) => this.consents.set(c), error: () => undefined });
    if (this.auth.role() !== 'ADMIN') {
      this.loadExports();
      this.api.deletionStatus().subscribe({ next: (d) => this.deletion.set(d), error: () => undefined });
    }
  }

  protected toggle(category: MessageCategory, channel: 'sms' | 'whatsapp' | 'email', on: boolean): void {
    this.prefs.update((list) => list.map((p) => (p.category === category ? { ...p, [channel]: on } : p)));
  }

  protected savePrefs(): void {
    this.busy.set(true);
    this.api.savePreferences(this.prefs()).subscribe({
      next: (p) => {
        this.busy.set(false);
        this.setPrefs(p);
        this.toast.success('Preferences saved');
      },
      error: (err) => this.fail(err),
    });
  }

  protected requestExport(): void {
    this.busy.set(true);
    this.api.requestExport().subscribe({
      next: () => {
        this.busy.set(false);
        this.toast.info("We're preparing your data. It takes about a minute.");
        this.loadExports();
      },
      error: (err) => {
        this.fail(err);
        if (errorCode(err) === 'EXPORT_RECENT') this.loadExports();
      },
    });
  }

  protected download(job: ExportJob): void {
    this.api.exportFile(job.id).subscribe({
      next: (blob) => saveBlob(blob, `progenie-data-${(job.readyAt ?? job.requestedAt).slice(0, 10)}.zip`),
      error: (err) => this.toast.error(errorMessage(err, 'This export has expired. Please request a new one.')),
    });
  }

  protected sendPrivacyRequest(): void {
    this.busy.set(true);
    this.support.privacyRequest(this.prSubject.trim(), this.prBody.trim()).subscribe({
      next: (t) => {
        this.busy.set(false);
        this.prSubject = this.prBody = '';
        this.toast.success(`Request ${t.ticketRef} sent to our grievance officer`);
        this.router.navigateByUrl(`${this.auth.role() === 'GENIE' ? '/genie' : '/account'}/tickets/${t.id}`);
      },
      error: (err) => this.fail(err),
    });
  }

  protected async requestDeletion(): Promise<void> {
    const { confirmed, value } = await this.confirm.ask({
      title: 'Delete your account?',
      message:
        'Your account will be deleted in 7 days. Until then you can log in and keep it. After that your personal details are removed for good.',
      confirmLabel: 'Schedule deletion',
      cancelLabel: 'Keep my account',
      tone: 'danger',
      input: { label: 'Why are you leaving? (optional)', placeholder: 'It helps us improve', multiline: true },
    });
    if (!confirmed) return;
    this.busy.set(true);
    this.api.requestDeletion(value || null).subscribe({
      next: (d) => {
        this.busy.set(false);
        this.deletion.set(d);
        this.auth.reloadMe().subscribe({ error: () => undefined });
        this.toast.success('Account deletion scheduled');
      },
      error: (err) => {
        this.fail(err);
        this.api.deletionStatus().subscribe({ next: (d) => this.deletion.set(d), error: () => undefined });
      },
    });
  }

  protected cancelDeletion(): void {
    this.busy.set(true);
    this.api.cancelDeletion().subscribe({
      next: () => {
        this.busy.set(false);
        this.auth.reloadMe().subscribe({ error: () => undefined });
        this.api.deletionStatus().subscribe({ next: (d) => this.deletion.set(d), error: () => undefined });
        this.toast.success('Deletion cancelled. Your account stays.');
      },
      error: (err) => this.fail(err),
    });
  }

  protected capitalise(text: string): string {
    return text.charAt(0).toUpperCase() + text.slice(1);
  }

  private setPrefs(p: NotificationPreference[]): void {
    this.prefs.set(p);
    this.savedPrefs.set(JSON.stringify(p));
  }

  private loadExports(): void {
    this.api.exports().subscribe({
      next: (list) => {
        this.exports.set(list);
        if (list.some((j) => j.status === 'PENDING')) this.startPolling();
        else this.stopPolling();
      },
      error: () => undefined,
    });
  }

  private startPolling(): void {
    this.poll ??= setInterval(() => this.loadExports(), 3000);
  }

  private stopPolling(): void {
    if (this.poll) clearInterval(this.poll);
    this.poll = null;
  }

  private fail(err: unknown): void {
    this.busy.set(false);
    this.toast.error(errorMessage(err));
  }

  protected readonly Bell = Bell;
  protected readonly Download = Download;
  protected readonly FileText = FileText;
  protected readonly LifeBuoy = LifeBuoy;
  protected readonly Trash = Trash;
}
