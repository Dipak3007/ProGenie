import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { LucideBadgePercent as BadgePercent, LucideDynamicIcon, LucidePlus as Plus } from '@lucide/angular';

import { API, errorMessage, query } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { AdminCategory, AdminCity, AdminCoupon, AdminService, CouponRedemption, CouponRequest, Page } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, PageHead, Pager, Skeleton, Spinner, TabItem, Tabs } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';

type Form = {
  id: number | null;
  code: string;
  title: string;
  description: string;
  discountType: 'PERCENT' | 'FLAT';
  discountValue: number | null;
  maxDiscount: number | null;
  minOrder: number | null;
  validFrom: string;
  validUntil: string;
  totalLimit: number | null;
  perCustomerLimit: number | null;
  firstBookingOnly: boolean;
  scope: 'ALL' | 'CATEGORY' | 'SERVICE' | 'CITY';
  targetId: number | null;
  listed: boolean;
};

const EMPTY: Form = {
  id: null, code: '', title: '', description: '', discountType: 'FLAT', discountValue: null, maxDiscount: null, minOrder: 0,
  validFrom: '', validUntil: '', totalLimit: null, perCustomerLimit: 1, firstBookingOnly: false, scope: 'ALL', targetId: null,
  listed: true,
};

/**
 * Admin → Coupons (Phase 3B): create and edit coupons, pause or resume them, and see how often each was used.
 * ProGenie pays every discount; the Genie's earnings never change.
 */
@Component({
  selector: 'pg-admin-coupons-page',
  imports: [CurrencyPipe, DatePipe, FormsModule, LucideDynamicIcon, Dialog, EmptyState, PageHead, Pager, Skeleton, Spinner, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Coupons" subtitle="Discounts on the service price, paid by ProGenie. Public coupons are shown at checkout; private codes work when typed.">
      <button type="button" class="btn-primary min-h-10 whitespace-nowrap" (click)="openNew()">
        <svg [lucideIcon]="Plus" [size]="16" aria-hidden="true"></svg> New coupon
      </button>
    </pg-page-head>

    <div class="mt-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <pg-tabs label="Filter coupons" [items]="tabs" [value]="tab()" (valueChange)="tab.set($event)" />
      <label class="sr-only" for="cp-q">Search coupons</label>
      <input id="cp-q" class="field sm:max-w-xs" placeholder="Search code or title" [ngModel]="q()" (ngModelChange)="q.set($event)" />
    </div>

    <div class="mt-5">
      @if (list.value(); as items) {
        @if (items.length) {
          <div class="card overflow-x-auto">
            <table class="w-full min-w-[52rem] text-left text-sm">
              <thead class="border-b border-line text-xs uppercase tracking-wider text-muted">
                <tr>
                  <th class="px-4 py-3 font-semibold">Coupon</th>
                  <th class="px-4 py-3 font-semibold">Discount</th>
                  <th class="px-4 py-3 font-semibold">Rules</th>
                  <th class="px-4 py-3 font-semibold">Used</th>
                  <th class="px-4 py-3 font-semibold">Status</th>
                  <th class="px-4 py-3 font-semibold"><span class="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody class="divide-y divide-line">
                @for (c of items; track c.id) {
                  <tr>
                    <td class="px-4 py-3">
                      <p class="font-display font-bold tracking-wide">{{ c.code }} @if (!c.listed) {<span class="ml-1 rounded-full bg-surface px-2 py-0.5 font-sans text-xs font-semibold text-muted">private</span>}</p>
                      <p class="text-muted">{{ c.title }}</p>
                    </td>
                    <td class="px-4 py-3 whitespace-nowrap">{{ discountText(c) }}</td>
                    <td class="px-4 py-3 text-xs text-muted">{{ rulesText(c) }}</td>
                    <td class="px-4 py-3 whitespace-nowrap">
                      <p class="font-semibold">{{ c.uses }}{{ c.totalLimit ? ' / ' + c.totalLimit : '' }}</p>
                      <p class="text-xs text-muted">{{ c.totalDiscount | currency: 'INR' }} given</p>
                    </td>
                    <td class="px-4 py-3"><pg-status [status]="c.status" /></td>
                    <td class="px-4 py-3">
                      <div class="flex justify-end gap-1">
                        <button type="button" class="btn-ghost min-h-9 px-3 text-xs" (click)="openUses(c)">Uses</button>
                        <button type="button" class="btn-ghost min-h-9 px-3 text-xs" (click)="openEdit(c)">Edit</button>
                        <button type="button" class="btn-ghost min-h-9 px-3 text-xs" [disabled]="busy()" (click)="toggle(c)">{{ c.status === 'ACTIVE' ? 'Pause' : 'Resume' }}</button>
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <pg-empty [icon]="BadgePercent" title="No coupons" message="Create a coupon to offer customers a discount." />
        }
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="3" />
      }
    </div>

    <!-- Create / edit -->
    <pg-dialog [open]="formOpen()" [heading]="form.id ? 'Edit ' + form.code : 'New coupon'" size="lg" (closed)="formOpen.set(false)">
      <form class="grid gap-4 sm:grid-cols-2" (ngSubmit)="save()">
        <div>
          <label class="label" for="cp-code">Code</label>
          <input id="cp-code" name="code" class="field uppercase" required pattern="[A-Za-z0-9]{4,20}" maxlength="20" [disabled]="!!form.id" [(ngModel)]="form.code" />
          <p class="mt-1 text-xs text-muted">4–20 letters or digits. It can't be changed later.</p>
        </div>
        <div>
          <label class="label" for="cp-title">Title</label>
          <input id="cp-title" name="title" class="field" required maxlength="80" [(ngModel)]="form.title" placeholder="e.g. ₹50 off your first booking" />
        </div>
        <div class="sm:col-span-2">
          <label class="label" for="cp-desc">Description <span class="font-normal text-muted">(optional)</span></label>
          <input id="cp-desc" name="desc" class="field" maxlength="300" [(ngModel)]="form.description" />
        </div>
        <div>
          <label class="label" for="cp-type">Discount</label>
          <select id="cp-type" name="type" class="field" [(ngModel)]="form.discountType">
            <option value="FLAT">Flat amount (₹)</option>
            <option value="PERCENT">Percentage (%)</option>
          </select>
        </div>
        <div>
          <label class="label" for="cp-value">{{ form.discountType === 'PERCENT' ? 'Percent off' : 'Amount off (₹)' }}</label>
          <input id="cp-value" name="value" type="number" min="1" [max]="form.discountType === 'PERCENT' ? 100 : 100000" class="field" required [(ngModel)]="form.discountValue" />
        </div>
        @if (form.discountType === 'PERCENT') {
          <div>
            <label class="label" for="cp-max">Maximum discount (₹, optional)</label>
            <input id="cp-max" name="max" type="number" min="1" class="field" [(ngModel)]="form.maxDiscount" />
          </div>
        }
        <div>
          <label class="label" for="cp-min">Minimum service price (₹)</label>
          <input id="cp-min" name="min" type="number" min="0" class="field" [(ngModel)]="form.minOrder" />
        </div>
        <div>
          <label class="label" for="cp-from">Valid from <span class="font-normal text-muted">(empty: now)</span></label>
          <input id="cp-from" name="from" type="datetime-local" class="field" [(ngModel)]="form.validFrom" />
        </div>
        <div>
          <label class="label" for="cp-until">Valid until <span class="font-normal text-muted">(empty: no end)</span></label>
          <input id="cp-until" name="until" type="datetime-local" class="field" [(ngModel)]="form.validUntil" />
        </div>
        <div>
          <label class="label" for="cp-total">Total uses <span class="font-normal text-muted">(empty: unlimited)</span></label>
          <input id="cp-total" name="total" type="number" min="1" class="field" [(ngModel)]="form.totalLimit" />
        </div>
        <div>
          <label class="label" for="cp-per">Uses per customer</label>
          <input id="cp-per" name="per" type="number" min="1" class="field" required [(ngModel)]="form.perCustomerLimit" />
        </div>
        <div>
          <label class="label" for="cp-scope">Applies to</label>
          <select id="cp-scope" name="scope" class="field" [(ngModel)]="form.scope" (ngModelChange)="form.targetId = null">
            <option value="ALL">Everything</option>
            <option value="CATEGORY">One category</option>
            <option value="SERVICE">One service</option>
            <option value="CITY">One city</option>
          </select>
        </div>
        @if (form.scope !== 'ALL') {
          <div>
            <label class="label" for="cp-target">{{ form.scope === 'CATEGORY' ? 'Category' : form.scope === 'SERVICE' ? 'Service' : 'City' }}</label>
            <select id="cp-target" name="target" class="field" required [(ngModel)]="form.targetId">
              @for (t of targets(); track t.id) {
                <option [ngValue]="t.id">{{ t.name }}</option>
              }
            </select>
          </div>
        }
        <label class="flex items-start gap-3 rounded-2xl border border-line p-3 text-sm sm:col-span-2">
          <input type="checkbox" name="first" class="mt-0.5 size-5 accent-brand" [(ngModel)]="form.firstBookingOnly" />
          <span><span class="font-semibold">First booking only.</span> Only for customers who haven't booked before.</span>
        </label>
        <label class="flex items-start gap-3 rounded-2xl border border-line p-3 text-sm sm:col-span-2">
          <input type="checkbox" name="listed" class="mt-0.5 size-5 accent-brand" [(ngModel)]="form.listed" />
          <span><span class="font-semibold">Show at checkout.</span> Untick for a private code that works only when typed.</span>
        </label>
        @if (formError()) {
          <p class="text-sm text-rose-700 sm:col-span-2" role="alert">{{ formError() }}</p>
        }
        <div class="flex flex-col-reverse gap-2 sm:col-span-2 sm:flex-row sm:justify-end">
          <button type="button" class="btn-ghost" (click)="formOpen.set(false)">Cancel</button>
          <button type="submit" class="btn-primary" [disabled]="busy()">
            @if (busy()) {
              <pg-spinner />
            }
            {{ form.id ? 'Save changes' : 'Create coupon' }}
          </button>
        </div>
      </form>
    </pg-dialog>

    <!-- Uses -->
    <pg-dialog [open]="!!usesOf()" [heading]="usesOf() ? 'Uses of ' + usesOf()!.code : ''" size="lg" (closed)="usesOf.set(null)">
      @if (uses.value(); as p) {
        @if (p.items.length) {
          <ul class="divide-y divide-line">
            @for (r of p.items; track r.id) {
              <li class="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
                <span>
                  <span class="font-semibold">{{ r.bookingRef }}</span> · {{ r.customerName }}
                  <span class="block text-xs text-muted">{{ r.createdAt | date: 'd MMM y, h:mm a' }} · booking {{ r.bookingStatus.toLowerCase().replace('_', ' ') }}</span>
                </span>
                <span class="flex items-center gap-2">{{ r.discountAmount | currency: 'INR' }} <pg-status [status]="r.status" /></span>
              </li>
            }
          </ul>
          <pg-pager [page]="p.page" [size]="p.size" [total]="p.total" (pageChange)="usesPage.set($event)" />
        } @else {
          <p class="text-sm text-muted">Not used yet.</p>
        }
      }
    </pg-dialog>
  `,
})
export default class AdminCouponsPage {
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);

  protected readonly tabs: TabItem[] = [
    { value: 'ACTIVE', label: 'Active' },
    { value: 'PAUSED', label: 'Paused' },
    { value: '', label: 'All' },
  ];
  protected readonly tab = signal('ACTIVE');
  protected readonly q = signal('');
  protected readonly list = httpResource<AdminCoupon[]>(
    () => `${API}/admin/coupons${query({ status: this.tab() || null, q: this.q().trim() || null })}`,
  );

  protected readonly formOpen = signal(false);
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected form: Form = { ...EMPTY };

  private readonly categories = httpResource<AdminCategory[]>(() => (this.formOpen() ? `${API}/admin/categories` : undefined));
  private readonly services = httpResource<AdminService[]>(() => (this.formOpen() ? `${API}/admin/services` : undefined));
  private readonly cities = httpResource<AdminCity[]>(() => (this.formOpen() ? `${API}/admin/cities` : undefined));
  /** Choices for the scope's target (category, service or city). */
  protected targets(): { id: number; name: string }[] {
    if (this.form.scope === 'CATEGORY') return (this.categories.value() ?? []).map((c) => ({ id: c.id, name: c.name }));
    if (this.form.scope === 'SERVICE') return (this.services.value() ?? []).map((s) => ({ id: s.id, name: s.name }));
    if (this.form.scope === 'CITY') return (this.cities.value() ?? []).map((c) => ({ id: c.cityId, name: c.name }));
    return [];
  }

  protected readonly usesOf = signal<AdminCoupon | null>(null);
  protected readonly usesPage = signal(0);
  protected readonly uses = httpResource<Page<CouponRedemption>>(() => {
    const c = this.usesOf();
    return c ? `${API}/admin/coupons/${c.id}/redemptions${query({ page: this.usesPage(), size: 20 })}` : undefined;
  });

  protected discountText(c: AdminCoupon): string {
    if (c.discountType === 'PERCENT') return `${c.discountValue}% off${c.maxDiscount ? `, up to ₹${c.maxDiscount}` : ''}`;
    return `₹${c.discountValue} off`;
  }

  protected rulesText(c: AdminCoupon): string {
    const parts: string[] = [];
    if (c.minOrder > 0) parts.push(`min ₹${c.minOrder}`);
    parts.push(c.perCustomerLimit === 1 ? 'once per customer' : `${c.perCustomerLimit}× per customer`);
    if (c.firstBookingOnly) parts.push('first booking');
    if (c.scope !== 'ALL') parts.push(c.categoryName ?? c.serviceName ?? c.cityName ?? c.scope.toLowerCase());
    if (c.validUntil) parts.push(`till ${new Date(c.validUntil).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}`);
    return parts.join(' · ');
  }

  protected openNew(): void {
    this.form = { ...EMPTY };
    this.formError.set(null);
    this.formOpen.set(true);
  }

  protected openEdit(c: AdminCoupon): void {
    this.form = {
      id: c.id, code: c.code, title: c.title, description: c.description ?? '', discountType: c.discountType as Form['discountType'],
      discountValue: c.discountValue, maxDiscount: c.maxDiscount ?? null, minOrder: c.minOrder, validFrom: local(c.validFrom),
      validUntil: c.validUntil ? local(c.validUntil) : '', totalLimit: c.totalLimit ?? null, perCustomerLimit: c.perCustomerLimit,
      firstBookingOnly: c.firstBookingOnly, scope: c.scope as Form['scope'], targetId: c.categoryId ?? c.serviceId ?? c.cityId ?? null,
      listed: c.listed,
    };
    this.formError.set(null);
    this.formOpen.set(true);
  }

  protected openUses(c: AdminCoupon): void {
    this.usesPage.set(0);
    this.usesOf.set(c);
  }

  protected save(): void {
    const f = this.form;
    const body: CouponRequest = {
      code: f.code.trim().toUpperCase(),
      title: f.title.trim(),
      description: f.description.trim() || null,
      discountType: f.discountType,
      discountValue: Number(f.discountValue),
      maxDiscount: f.discountType === 'PERCENT' && f.maxDiscount ? Number(f.maxDiscount) : null,
      minOrder: Number(f.minOrder) || 0,
      validFrom: f.validFrom ? new Date(f.validFrom).toISOString() : null,
      validUntil: f.validUntil ? new Date(f.validUntil).toISOString() : null,
      totalLimit: f.totalLimit ? Number(f.totalLimit) : null,
      perCustomerLimit: Number(f.perCustomerLimit) || 1,
      firstBookingOnly: f.firstBookingOnly,
      scope: f.scope,
      categoryId: f.scope === 'CATEGORY' ? f.targetId : null,
      serviceId: f.scope === 'SERVICE' ? f.targetId : null,
      cityId: f.scope === 'CITY' ? f.targetId : null,
      listed: f.listed,
    };
    this.busy.set(true);
    this.formError.set(null);
    const request = f.id ? this.api.updateCoupon(f.id, body) : this.api.createCoupon(body);
    request.subscribe({
      next: (c) => {
        this.busy.set(false);
        this.formOpen.set(false);
        this.toast.success(f.id ? `${c.code} saved` : `${c.code} created`);
        this.list.reload();
      },
      error: (err) => {
        this.busy.set(false);
        this.formError.set(errorMessage(err));
      },
    });
  }

  protected toggle(c: AdminCoupon): void {
    this.busy.set(true);
    this.api.setCouponActive(c.id, c.status !== 'ACTIVE').subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.toast.success(`${updated.code} ${updated.status === 'ACTIVE' ? 'resumed' : 'paused'}`);
        this.list.reload();
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected readonly BadgePercent = BadgePercent;
  protected readonly Plus = Plus;
}

/** ISO instant → the value a datetime-local input shows (local time). */
function local(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
