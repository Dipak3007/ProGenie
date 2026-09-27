import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  LucideCircleCheck as CircleCheck,
  LucideDynamicIcon,
  LucideEye as Eye,
  LucideFileText as FileText,
  LucideLock as Lock,
  LucidePlus as Plus,
  LucideTrash2 as Trash,
  LucideUpload as Upload,
} from '@lucide/angular';

import { API, ApiClient, errorMessage } from '../../core/api/api';
import { GenieApi } from '../../core/api/genie-api';
import { AvailabilityWindow, Category, DocType, GenieDocument, MyProfile, OnboardingStep, ServiceItem } from '../../core/api/models';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { humanize } from '../../shared/format';
import { PageHead, Spinner } from '../../shared/ui/kit';
import { AHMEDABAD, LatLng, MapPicker } from '../../shared/ui/map-picker';
import { StatusBadge } from '../../shared/ui/status-badge';
import { AvailabilityEditor } from './availability-editor';
import { ALL_STEPS, GenieStore, STEP_TEXT } from './genie-store';

interface OfferRow {
  serviceId: number;
  name: string;
  categoryName: string;
  basePrice: number;
  durationMinutes: number;
  price: number;
}

const DOC_TYPES: { value: DocType; label: string; needsNumber: boolean }[] = [
  { value: 'AADHAAR', label: 'Aadhaar card', needsNumber: true },
  { value: 'PAN', label: 'PAN card', needsNumber: true },
  { value: 'SELFIE', label: 'Selfie photo', needsNumber: false },
  { value: 'CERTIFICATE', label: 'Skill certificate', needsNumber: false },
  { value: 'OTHER', label: 'Other document', needsNumber: false },
];
const MAX_BYTES = 5 * 1024 * 1024;

@Component({
  selector: 'pg-onboarding-page',
  imports: [CurrencyPipe, DatePipe, FormsModule, LucideDynamicIcon, AvailabilityEditor, MapPicker, PageHead, Spinner, StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.profile(); as p) {
      <pg-page-head
        [title]="store.approved() ? 'Profile & services' : 'Get verified'"
        [subtitle]="store.approved() ? 'What customers see on your profile, and what you offer.' : 'Four short sections. Save each one, then submit for review.'"
      >
        <pg-status [status]="p.verificationStatus" />
      </pg-page-head>

      <!-- Step chips -->
      <nav class="-mx-4 mt-5 overflow-x-auto px-4 sm:mx-0 sm:px-0" aria-label="Sections">
        <ol class="flex min-w-max gap-2">
          @for (s of sections; track s.id; let i = $index) {
            <li>
              <a
                [href]="'#' + s.id"
                (click)="$event.preventDefault(); scrollTo(s.id)"
                class="flex min-h-10 items-center gap-2 rounded-full border px-4 text-sm font-semibold"
                [class]="sectionDone(s.id) ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : 'border-line bg-white text-ink'"
              >
                @if (sectionDone(s.id)) {
                  <svg [lucideIcon]="CircleCheck" [size]="16" aria-hidden="true"></svg>
                } @else {
                  <span class="grid size-5 place-items-center rounded-full bg-navy text-[11px] text-white">{{ i + 1 }}</span>
                }
                {{ s.label }}
              </a>
            </li>
          }
        </ol>
      </nav>

      @if (p.verificationStatus === 'NEEDS_CHANGES' && p.verificationNote) {
        <p class="mt-5 rounded-2xl bg-amber-50 px-4 py-3 text-sm text-amber-900 ring-1 ring-amber-200">
          <span class="font-semibold">Requested by our team:</span> {{ p.verificationNote }}
        </p>
      }
      @if (p.verificationStatus === 'UNDER_REVIEW') {
        <p class="mt-5 flex items-start gap-2 rounded-2xl bg-surface px-4 py-3 text-sm text-muted ring-1 ring-line">
          <svg [lucideIcon]="Lock" [size]="16" class="mt-0.5 shrink-0" aria-hidden="true"></svg>
          Your profile is being reviewed. Documents are locked until the decision; you can still adjust hours and prices.
        </p>
      }

      <!-- 1. Profile -->
      <section id="profile" class="card mt-6 scroll-mt-40 p-5 sm:p-6" aria-labelledby="profile-h">
        <h2 id="profile-h" class="text-lg font-bold">1 · About you</h2>
        <p class="mt-1 text-sm text-muted">Customers read this before booking. Keep it friendly and specific.</p>
        <form class="mt-5 grid gap-4 lg:grid-cols-2" (ngSubmit)="saveProfile()" #pf="ngForm">
          <div class="lg:col-span-2">
            <label class="label" for="bio">Short bio <span class="font-normal text-muted">({{ bio.trim().length }}/600, at least 20)</span></label>
            <textarea
              id="bio"
              name="bio"
              class="field min-h-28"
              maxlength="600"
              [(ngModel)]="bio"
              placeholder="e.g. 8 years fixing wiring, fans and inverters across west Ahmedabad. Neat work, fair prices, always on time."
            ></textarea>
          </div>
          <div>
            <label class="label" for="exp">Years of experience</label>
            <input id="exp" name="exp" type="number" min="0" max="60" class="field" [(ngModel)]="experience" required />
          </div>
          <div>
            <label class="label" for="upi">Payout UPI ID</label>
            <input id="upi" name="upi" class="field" [(ngModel)]="upi" pattern="^[a-zA-Z0-9._\\-]{2,256}@[a-zA-Z]{2,64}$" placeholder="yourname@okaxis" autocomplete="off" />
            <p class="mt-1 text-xs text-muted">Weekly earnings are sent here every Monday.</p>
          </div>
          <div class="lg:col-span-2">
            <span class="label">Base location <span class="font-normal text-muted">(where you usually start from; customers see only the area)</span></span>
            <pg-map-picker [lat]="base()?.lat ?? null" [lng]="base()?.lng ?? null" label="Your base location" (moved)="base.set($event)" />
          </div>
          <div>
            <label class="label" for="area">Area name</label>
            <input id="area" name="area" class="field" maxlength="80" [(ngModel)]="area" placeholder="Navrangpura" required />
          </div>
          <div>
            <label class="label" for="radius">I travel up to <span class="font-semibold text-brand">{{ radius }} km</span></label>
            <input id="radius" name="radius" type="range" min="1" max="50" class="mt-3 w-full accent-brand" [(ngModel)]="radius" />
            <p class="mt-1 text-xs text-muted">Customers farther than this can't book you.</p>
          </div>
          <div class="flex items-center justify-end gap-3 lg:col-span-2">
            @if (!base()) {
              <span class="text-xs text-muted">Drop a pin on the map first.</span>
            }
            <button type="submit" class="btn-primary" [disabled]="savingProfile() || !pf.valid || !base()">
              @if (savingProfile()) {
                <pg-spinner />
              }
              Save profile
            </button>
          </div>
        </form>
      </section>

      <!-- 2. Services -->
      <section id="services" class="card mt-6 scroll-mt-40 p-5 sm:p-6" aria-labelledby="services-h">
        <h2 id="services-h" class="text-lg font-bold">2 · Services and prices</h2>
        <p class="mt-1 text-sm text-muted">Offer what you're great at. You can charge between half and three times the base price.</p>

        <div class="mt-5 space-y-2">
          @for (o of offers(); track o.serviceId; let i = $index) {
            <div class="flex flex-col gap-3 rounded-2xl border border-line p-4 sm:flex-row sm:items-center">
              <div class="min-w-0 flex-1">
                <p class="font-semibold">{{ o.name }}</p>
                <p class="text-xs text-muted">{{ o.categoryName }} · ~{{ o.durationMinutes }} min · base {{ o.basePrice | currency: 'INR' : 'symbol' : '1.0-0' }}</p>
              </div>
              <div class="flex items-center gap-2">
                <label class="sr-only" [for]="'price-' + o.serviceId">Your price for {{ o.name }}</label>
                <span class="text-muted">₹</span>
                <input
                  [id]="'price-' + o.serviceId"
                  type="number"
                  class="field w-28 py-2"
                  [min]="o.basePrice * 0.5"
                  [max]="o.basePrice * 3"
                  [value]="o.price"
                  (input)="setPrice(i, +$any($event.target).value)"
                />
                <button type="button" class="grid size-10 place-items-center rounded-full text-muted hover:bg-rose-50 hover:text-rose-700" [attr.aria-label]="'Remove ' + o.name" (click)="removeOffer(i)">
                  <svg [lucideIcon]="Trash" [size]="16" aria-hidden="true"></svg>
                </button>
              </div>
              @if (priceError(o)) {
                <p class="text-xs text-rose-700 sm:basis-full">{{ priceError(o) }}</p>
              }
            </div>
          } @empty {
            <p class="rounded-2xl border border-dashed border-line p-4 text-sm text-muted">No services yet. Add them below.</p>
          }
        </div>

        <div class="mt-5 rounded-2xl bg-surface p-4">
          <p class="text-sm font-semibold">Add services</p>
          <div class="mt-3 flex flex-col gap-3 sm:flex-row">
            <label class="sr-only" for="cat">Category</label>
            <select id="cat" class="field sm:max-w-xs" [ngModel]="categorySlug()" (ngModelChange)="pickCategory($event)">
              <option [ngValue]="null">Choose a category…</option>
              @for (c of categories.value() ?? []; track c.id) {
                <option [ngValue]="c.slug">{{ c.name }}</option>
              }
            </select>
          </div>
          @if (categoryServices().length) {
            <ul class="mt-3 grid gap-2 sm:grid-cols-2">
              @for (s of categoryServices(); track s.id) {
                <li>
                  <button
                    type="button"
                    class="flex w-full items-center justify-between gap-3 rounded-xl border border-line bg-white px-4 py-3 text-left text-sm transition hover:border-brand disabled:opacity-50"
                    [disabled]="hasOffer(s.id)"
                    (click)="addOffer(s)"
                  >
                    <span>
                      <span class="block font-semibold">{{ s.name }}</span>
                      <span class="text-xs text-muted">base {{ s.basePrice | currency: 'INR' : 'symbol' : '1.0-0' }}</span>
                    </span>
                    @if (hasOffer(s.id)) {
                      <svg [lucideIcon]="CircleCheck" [size]="18" class="text-emerald-600" aria-hidden="true"></svg>
                    } @else {
                      <svg [lucideIcon]="Plus" [size]="18" class="text-brand" aria-hidden="true"></svg>
                    }
                  </button>
                </li>
              }
            </ul>
          }
        </div>

        <div class="mt-4 flex justify-end">
          <button type="button" class="btn-primary" [disabled]="savingServices() || !servicesDirty() || offers().length === 0 || hasPriceErrors()" (click)="saveServices()">
            @if (savingServices()) {
              <pg-spinner />
            }
            Save services
          </button>
        </div>
      </section>

      <!-- 3. Availability -->
      <section id="availability" class="card mt-6 scroll-mt-40 p-5 sm:p-6" aria-labelledby="hours-h">
        <h2 id="hours-h" class="text-lg font-bold">3 · Working hours</h2>
        <p class="mt-1 text-sm text-muted">Customers can only book whole jobs inside these hours. Add holidays on the Availability page.</p>
        <pg-availability-editor class="mt-5" [windows]="p.availability" (saved)="onHoursSaved($event)" />
      </section>

      <!-- 4. Documents -->
      <section id="documents" class="card mt-6 scroll-mt-40 p-5 sm:p-6" aria-labelledby="docs-h">
        <h2 id="docs-h" class="text-lg font-bold">4 · Identity documents</h2>
        <p class="mt-1 text-sm text-muted">
          Needed: an Aadhaar or PAN card and a selfie. Files stay private to our verification team; we store only the last 4 characters of ID numbers.
        </p>

        <ul class="mt-5 space-y-2">
          @for (d of p.documents; track d.id) {
            <li class="flex flex-col gap-3 rounded-2xl border border-line p-4 sm:flex-row sm:items-center">
              <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-mist text-brand-600">
                <svg [lucideIcon]="FileText" [size]="18" aria-hidden="true"></svg>
              </span>
              <div class="min-w-0 flex-1">
                <p class="flex flex-wrap items-center gap-2 font-semibold">
                  {{ docLabel(d.docType) }} <pg-status [status]="d.status" />
                </p>
                <p class="truncate text-xs text-muted">
                  {{ d.maskedNumber ? d.maskedNumber + ' · ' : '' }}{{ d.fileName }} · {{ d.createdAt | date: 'd MMM' }}
                </p>
                @if (d.reviewNote) {
                  <p class="mt-1 text-xs text-rose-700">Note from our team: {{ d.reviewNote }}</p>
                }
              </div>
              <div class="flex gap-2">
                <button type="button" class="btn-ghost min-h-10 px-4" (click)="viewDoc(d)"><svg [lucideIcon]="Eye" [size]="16" aria-hidden="true"></svg> View</button>
                @if (docsEditable() && d.status !== 'APPROVED') {
                  <button type="button" class="grid size-10 place-items-center rounded-full text-muted hover:bg-rose-50 hover:text-rose-700" [attr.aria-label]="'Delete ' + docLabel(d.docType)" (click)="deleteDoc(d)">
                    <svg [lucideIcon]="Trash" [size]="16" aria-hidden="true"></svg>
                  </button>
                }
              </div>
            </li>
          } @empty {
            <li class="rounded-2xl border border-dashed border-line p-4 text-sm text-muted">No documents uploaded yet.</li>
          }
        </ul>

        @if (docsEditable()) {
          <form class="mt-5 grid gap-4 rounded-2xl bg-surface p-4 sm:grid-cols-2" (ngSubmit)="upload()">
            <div>
              <label class="label" for="doctype">Document</label>
              <select id="doctype" name="doctype" class="field" [(ngModel)]="docType">
                @for (t of docTypes; track t.value) {
                  <option [ngValue]="t.value">{{ t.label }}</option>
                }
              </select>
            </div>
            @if (needsNumber()) {
              <div>
                <label class="label" for="last4">Last 4 characters of the number</label>
                <input id="last4" name="last4" class="field uppercase" maxlength="4" [(ngModel)]="last4" placeholder="1234" autocomplete="off" />
              </div>
            }
            <div class="sm:col-span-2">
              <label class="label" for="file">File <span class="font-normal text-muted">({{ docType === 'SELFIE' ? 'JPG, PNG or WEBP' : 'PDF, JPG, PNG or WEBP' }}, up to 5 MB)</span></label>
              <input
                id="file"
                name="file"
                type="file"
                class="block w-full rounded-xl border border-dashed border-line bg-white p-3 text-sm file:mr-3 file:rounded-full file:border-0 file:bg-brand file:px-4 file:py-2 file:font-semibold file:text-white"
                [accept]="docType === 'SELFIE' ? 'image/jpeg,image/png,image/webp' : 'application/pdf,image/jpeg,image/png,image/webp'"
                [attr.capture]="docType === 'SELFIE' ? 'user' : null"
                (change)="pickFile($event)"
              />
              @if (fileError()) {
                <p class="mt-1 text-xs text-rose-700">{{ fileError() }}</p>
              }
            </div>
            <div class="flex justify-end sm:col-span-2">
              <button type="submit" class="btn-primary" [disabled]="uploading() || !file() || (needsNumber() && last4.trim().length !== 4)">
                @if (uploading()) {
                  <pg-spinner />
                } @else {
                  <svg [lucideIcon]="Upload" [size]="18" aria-hidden="true"></svg>
                }
                Upload
              </button>
            </div>
          </form>
        }
      </section>

      <!-- Submit -->
      @if (!store.approved()) {
        <section class="card mt-6 p-5 sm:p-6" aria-labelledby="submit-h">
          <h2 id="submit-h" class="text-lg font-bold">Submit for review</h2>
          @if (p.missingSteps.length) {
            <p class="mt-1 text-sm text-muted">Still to do:</p>
            <ul class="mt-2 flex flex-wrap gap-2">
              @for (s of p.missingSteps; track s) {
                <li><button type="button" class="chip bg-amber-50 text-amber-900" (click)="scrollTo(stepText[s].section)">{{ stepText[s].title }}</button></li>
              }
            </ul>
          } @else if (p.verificationStatus === 'UNDER_REVIEW') {
            <p class="mt-1 text-sm text-muted">Submitted {{ p.submittedAt | date: 'd MMM, h:mm a' }}. We'll notify you when there's a decision.</p>
          } @else {
            <p class="mt-1 text-sm text-muted">All set. Our team usually reviews within 24–48 hours.</p>
          }
          <button type="button" class="btn-gold mt-4 min-h-12 w-full sm:w-auto" [disabled]="!p.canSubmit || submitting()" (click)="submit()">
            @if (submitting()) {
              <pg-spinner />
            }
            Submit for review
          </button>
        </section>
      }
    } @else {
      <div class="card h-48 animate-pulse"></div>
    }
  `,
})
export default class OnboardingPage {
  protected readonly store = inject(GenieStore);
  private readonly api = inject(GenieApi);
  private readonly catalog = inject(ApiClient);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly router = inject(Router);

  protected readonly sections = [
    { id: 'profile' as const, label: 'About you' },
    { id: 'services' as const, label: 'Services' },
    { id: 'availability' as const, label: 'Hours' },
    { id: 'documents' as const, label: 'Documents' },
  ];
  protected readonly stepText = STEP_TEXT;
  protected readonly docTypes = DOC_TYPES;

  // profile form
  protected bio = '';
  protected experience = 0;
  protected upi = '';
  protected area = '';
  protected radius = 10;
  protected readonly base = signal<LatLng | null>(null);
  protected readonly savingProfile = signal(false);

  // services
  protected readonly categories = httpResource<Category[]>(() => `${API}/categories`);
  protected readonly categorySlug = signal<string | null>(null);
  protected readonly categoryServices = signal<ServiceItem[]>([]);
  private categoryName = '';
  protected readonly offers = signal<OfferRow[]>([]);
  protected readonly servicesDirty = signal(false);
  protected readonly savingServices = signal(false);

  // documents
  protected docType: DocType = 'AADHAAR';
  protected last4 = '';
  protected readonly file = signal<File | null>(null);
  protected readonly fileError = signal<string | null>(null);
  protected readonly uploading = signal(false);
  protected readonly submitting = signal(false);

  protected readonly docsEditable = computed(() => ['REGISTERED', 'NEEDS_CHANGES', 'APPROVED'].includes(this.store.status() ?? ''));

  constructor() {
    // Fill the forms from the profile (first load and after each save).
    effect(() => {
      const p = this.store.profile();
      if (!p) return;
      untracked(() => this.fill(p));
    });
    // Jump to #section when arriving from the dashboard checklist.
    effect(() => {
      if (!this.store.profile()) return;
      const hash = location.hash.slice(1);
      if (hash) untracked(() => setTimeout(() => this.scrollTo(hash), 50));
    });
  }

  private filledFor: string | null = null;

  private fill(p: MyProfile): void {
    // Do not wipe unsaved typing when only another section was saved.
    if (this.filledFor !== p.id) {
      this.bio = p.bio ?? '';
      this.experience = p.experienceYears ?? 0;
      this.upi = p.payoutUpiId ?? '';
      this.area = p.baseArea ?? '';
      this.radius = p.serviceRadiusKm ?? 10;
      this.base.set(p.baseLat !== null && p.baseLng !== null ? { lat: p.baseLat, lng: p.baseLng } : null);
      this.filledFor = p.id;
    }
    if (!this.servicesDirty()) {
      this.offers.set(
        p.services.map((s) => ({
          serviceId: s.serviceId,
          name: s.name,
          categoryName: s.categoryName,
          basePrice: s.basePrice,
          durationMinutes: s.durationMinutes,
          price: s.price,
        })),
      );
    }
  }

  protected sectionDone(id: 'profile' | 'services' | 'availability' | 'documents'): boolean {
    const missing = this.store.profile()?.missingSteps ?? ALL_STEPS;
    return !missing.some((s: OnboardingStep) => STEP_TEXT[s].section === id);
  }

  protected scrollTo(id: string): void {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  // ---------------------------------------------------------------- profile
  protected saveProfile(): void {
    const base = this.base() ?? AHMEDABAD;
    this.savingProfile.set(true);
    this.api
      .updateProfile({
        bio: this.bio.trim() || null,
        experienceYears: Number(this.experience) || 0,
        baseArea: this.area.trim() || null,
        baseLat: base.lat,
        baseLng: base.lng,
        serviceRadiusKm: Number(this.radius) || 10,
        payoutUpiId: this.upi.trim() || null,
      })
      .subscribe({
        next: (p) => {
          this.savingProfile.set(false);
          this.store.set(p);
          this.toast.success(this.bio.trim().length < 20 ? 'Saved. Add a slightly longer bio (20+ characters) to complete this step.' : 'Profile saved');
        },
        error: (err) => {
          this.savingProfile.set(false);
          this.toast.error(errorMessage(err));
        },
      });
  }

  // ---------------------------------------------------------------- services
  protected pickCategory(slug: string | null): void {
    this.categorySlug.set(slug);
    this.categoryServices.set([]);
    if (!slug) return;
    this.catalog.categoryDetail(slug).subscribe((d) => {
      this.categoryName = d.category.name;
      this.categoryServices.set(d.services);
    });
  }

  protected hasOffer(serviceId: number): boolean {
    return this.offers().some((o) => o.serviceId === serviceId);
  }

  protected addOffer(s: ServiceItem): void {
    this.offers.update((list) => [
      ...list,
      { serviceId: s.id, name: s.name, categoryName: this.categoryName, basePrice: s.basePrice, durationMinutes: s.durationMinutes, price: s.basePrice },
    ]);
    this.servicesDirty.set(true);
  }

  protected removeOffer(i: number): void {
    this.offers.update((list) => list.filter((_, idx) => idx !== i));
    this.servicesDirty.set(true);
  }

  protected setPrice(i: number, price: number): void {
    this.offers.update((list) => list.map((o, idx) => (idx === i ? { ...o, price } : o)));
    this.servicesDirty.set(true);
  }

  protected priceError(o: OfferRow): string | null {
    if (!o.price || o.price < o.basePrice * 0.5) return `At least ₹${Math.ceil(o.basePrice * 0.5)}`;
    if (o.price > o.basePrice * 3) return `At most ₹${Math.floor(o.basePrice * 3)}`;
    return null;
  }

  protected hasPriceErrors(): boolean {
    return this.offers().some((o) => this.priceError(o) !== null);
  }

  protected saveServices(): void {
    this.savingServices.set(true);
    const body = this.offers().map((o) => ({ serviceId: o.serviceId, priceOverride: o.price === o.basePrice ? null : o.price }));
    this.api.saveServices(body).subscribe({
      next: (services) => {
        this.savingServices.set(false);
        this.servicesDirty.set(false);
        const p = this.store.profile();
        if (p) this.store.set({ ...p, services, missingSteps: p.missingSteps.filter((s) => s !== 'SERVICES') });
        this.toast.success('Services saved');
        this.store.load();
      },
      error: (err) => {
        this.savingServices.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  // ---------------------------------------------------------------- hours
  protected onHoursSaved(windows: AvailabilityWindow[]): void {
    const p = this.store.profile();
    if (p) this.store.set({ ...p, availability: windows });
    this.store.load();
  }

  // ---------------------------------------------------------------- documents
  protected needsNumber(): boolean {
    return DOC_TYPES.find((t) => t.value === this.docType)?.needsNumber ?? false;
  }

  protected docLabel(type: string): string {
    return DOC_TYPES.find((t) => t.value === type)?.label ?? humanize(type);
  }

  protected pickFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const f = input.files?.[0] ?? null;
    this.fileError.set(null);
    if (f && f.size > MAX_BYTES) {
      this.fileError.set('That file is larger than 5 MB. Please pick a smaller one.');
      input.value = '';
      this.file.set(null);
      return;
    }
    this.file.set(f);
  }

  protected upload(): void {
    const f = this.file();
    if (!f) return;
    this.uploading.set(true);
    this.api.uploadDocument(this.docType, this.needsNumber() ? this.last4.trim() : null, f).subscribe({
      next: () => {
        this.uploading.set(false);
        this.file.set(null);
        this.last4 = '';
        const input = document.getElementById('file') as HTMLInputElement | null;
        if (input) input.value = '';
        this.toast.success(`${this.docLabel(this.docType)} uploaded`);
        this.docType = this.docType === 'AADHAAR' || this.docType === 'PAN' ? 'SELFIE' : this.docType;
        this.store.load();
      },
      error: (err) => {
        this.uploading.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected viewDoc(d: GenieDocument): void {
    // Open the tab synchronously (popup blockers), then point it at the file once downloaded with the auth header.
    const tab = window.open('', '_blank');
    this.api.documentFile(d.id).subscribe({
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

  protected async deleteDoc(d: GenieDocument): Promise<void> {
    const { confirmed } = await this.confirm.ask({ title: `Delete ${this.docLabel(d.docType)}?`, confirmLabel: 'Delete', tone: 'danger' });
    if (!confirmed) return;
    this.api.deleteDocument(d.id).subscribe({
      next: () => {
        this.toast.success('Document deleted');
        this.store.load();
      },
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }

  // ---------------------------------------------------------------- submit
  protected submit(): void {
    this.submitting.set(true);
    this.api.submit().subscribe({
      next: (p) => {
        this.submitting.set(false);
        this.store.set(p);
        this.toast.success('Submitted! We will review your profile within 24–48 hours.');
        this.router.navigateByUrl('/genie');
      },
      error: (err) => {
        this.submitting.set(false);
        this.toast.error(errorMessage(err));
        this.store.load();
      },
    });
  }

  protected readonly CircleCheck = CircleCheck;
  protected readonly Eye = Eye;
  protected readonly FileText = FileText;
  protected readonly Lock = Lock;
  protected readonly Plus = Plus;
  protected readonly Trash = Trash;
  protected readonly Upload = Upload;
}
