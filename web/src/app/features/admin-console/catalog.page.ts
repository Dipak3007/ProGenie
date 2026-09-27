import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LucideDynamicIcon, LucidePencil as Pencil, LucidePlus as Plus } from '@lucide/angular';

import { API, errorMessage, query } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { AdminCategory, AdminService, CityPricing } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { Dialog } from '../../shared/ui/dialog';
import { PageHead, Spinner, TabItem, Tabs } from '../../shared/ui/kit';
import { categoryIcon } from '../../shared/ui/icons';
import { StatusBadge } from '../../shared/ui/status-badge';

type Tab = 'services' | 'categories' | 'pricing';

function slugify(text: string): string {
  return text
    .toLowerCase()
    .trim()
    .replace(/&/g, 'and')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}

@Component({
  selector: 'pg-admin-catalog-page',
  imports: [CurrencyPipe, FormsModule, LucideDynamicIcon, Dialog, PageHead, Spinner, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Catalog & pricing" subtitle="Categories and services customers can book, and each city's commission and travel fees.">
      @if (tab() === 'services') {
        <button type="button" class="btn-primary" (click)="editService(null)"><svg [lucideIcon]="Plus" [size]="18" aria-hidden="true"></svg> New service</button>
      } @else if (tab() === 'categories') {
        <button type="button" class="btn-primary" (click)="editCategory(null)"><svg [lucideIcon]="Plus" [size]="18" aria-hidden="true"></svg> New category</button>
      }
    </pg-page-head>

    <pg-tabs class="mt-5" [items]="tabs" [value]="tab()" (valueChange)="tab.set($any($event))" />

    @switch (tab()) {
      @case ('services') {
        <div class="mt-5">
          <select class="field w-full py-2.5 sm:w-72" [ngModel]="categoryFilter()" (ngModelChange)="categoryFilter.set($event)" aria-label="Filter by category">
            <option [ngValue]="null">All categories</option>
            @for (c of categories.value() ?? []; track c.id) {
              <option [ngValue]="c.id">{{ c.name }}</option>
            }
          </select>
          <div class="card mt-4 overflow-x-auto">
            <table class="w-full min-w-[40rem] text-left text-sm">
              <thead class="bg-surface text-xs uppercase tracking-wider text-muted">
                <tr>
                  <th class="px-4 py-3 font-semibold">Service</th>
                  <th class="px-4 py-3 font-semibold">Category</th>
                  <th class="px-4 py-3 text-right font-semibold">Base price</th>
                  <th class="px-4 py-3 text-right font-semibold">Duration</th>
                  <th class="px-4 py-3 text-right font-semibold">Genies</th>
                  <th class="px-4 py-3 font-semibold">Status</th>
                  <th class="px-4 py-3"><span class="sr-only">Edit</span></th>
                </tr>
              </thead>
              <tbody class="divide-y divide-line">
                @for (s of services.value() ?? []; track s.id) {
                  <tr [class.opacity-60]="!s.active">
                    <td class="px-4 py-3 font-medium">{{ s.name }}</td>
                    <td class="px-4 py-3 text-muted">{{ s.categoryName }}</td>
                    <td class="px-4 py-3 text-right tabular-nums">{{ s.basePrice | currency: 'INR' : 'symbol' : '1.0-0' }}</td>
                    <td class="px-4 py-3 text-right tabular-nums">{{ s.durationMinutes }} min</td>
                    <td class="px-4 py-3 text-right tabular-nums">{{ s.genieCount }}</td>
                    <td class="px-4 py-3"><pg-status [status]="s.active ? 'ACTIVE' : 'INACTIVE'" [label]="s.active ? 'Live' : 'Hidden'" [tone]="s.active ? 'success' : 'neutral'" /></td>
                    <td class="px-4 py-3 text-right">
                      <button type="button" class="grid size-9 place-items-center rounded-full text-muted hover:bg-surface hover:text-brand" [attr.aria-label]="'Edit ' + s.name" (click)="editService(s)">
                        <svg [lucideIcon]="Pencil" [size]="16" aria-hidden="true"></svg>
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
      @case ('categories') {
        <div class="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          @for (c of categories.value() ?? []; track c.id) {
            <article class="card flex items-start gap-3 p-4" [class.opacity-60]="!c.active">
              <span class="grid size-11 shrink-0 place-items-center rounded-xl bg-brand-mist text-brand-600">
                <svg [lucideIcon]="icon(c.icon)" [size]="20" aria-hidden="true"></svg>
              </span>
              <div class="min-w-0 flex-1">
                <p class="font-semibold">{{ c.name }}</p>
                <p class="text-xs text-muted">/{{ c.slug }} · {{ c.serviceCount }} services · order {{ c.sortOrder }}</p>
                <pg-status class="mt-2 inline-block" [status]="c.active ? 'ACTIVE' : 'INACTIVE'" [label]="c.active ? 'Live' : 'Hidden'" [tone]="c.active ? 'success' : 'neutral'" />
              </div>
              <button type="button" class="grid size-9 place-items-center rounded-full text-muted hover:bg-surface hover:text-brand" [attr.aria-label]="'Edit ' + c.name" (click)="editCategory(c)">
                <svg [lucideIcon]="Pencil" [size]="16" aria-hidden="true"></svg>
              </button>
            </article>
          }
        </div>
      }
      @case ('pricing') {
        <div class="mt-5 space-y-4">
          @for (c of cities.value() ?? []; track c.cityId) {
            <form class="card p-5" (ngSubmit)="savePricing(c)">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <h2 class="text-lg font-bold">{{ c.name }}, {{ c.state }}</h2>
                <pg-status [status]="c.active ? 'ACTIVE' : 'INACTIVE'" [label]="c.active ? 'Live' : 'Coming soon'" [tone]="c.active ? 'success' : 'neutral'" />
              </div>
              <div class="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
                <div>
                  <label class="label" [for]="'cr-' + c.cityId">Commission (%)</label>
                  <input [id]="'cr-' + c.cityId" [name]="'cr-' + c.cityId" type="number" step="0.5" min="0" max="50" class="field" [ngModel]="percent(c)" (ngModelChange)="c.commissionRate = +$event / 100" />
                </div>
                <div>
                  <label class="label" [for]="'fk-' + c.cityId">Free travel up to (km)</label>
                  <input [id]="'fk-' + c.cityId" [name]="'fk-' + c.cityId" type="number" step="0.5" min="0" class="field" [(ngModel)]="c.travelFreeKm" />
                </div>
                <div>
                  <label class="label" [for]="'bf-' + c.cityId">Base travel fee (₹)</label>
                  <input [id]="'bf-' + c.cityId" [name]="'bf-' + c.cityId" type="number" min="0" class="field" [(ngModel)]="c.travelBaseFee" />
                </div>
                <div>
                  <label class="label" [for]="'pk-' + c.cityId">Per extra km (₹)</label>
                  <input [id]="'pk-' + c.cityId" [name]="'pk-' + c.cityId" type="number" min="0" class="field" [(ngModel)]="c.travelPerKm" />
                </div>
                <div>
                  <label class="label" [for]="'cap-' + c.cityId">Travel fee cap (₹)</label>
                  <input [id]="'cap-' + c.cityId" [name]="'cap-' + c.cityId" type="number" min="0" class="field" [(ngModel)]="c.travelFeeCap" />
                </div>
              </div>
              <div class="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <p class="text-sm text-muted">
                  Example, 6 km away: travel {{ example(c) | currency: 'INR' : 'symbol' : '1.0-0' }} · commission on a ₹500 job {{ c.commissionRate * 500 | currency: 'INR' : 'symbol' : '1.0-2' }}
                </p>
                <button type="submit" class="btn-primary" [disabled]="savingCity() === c.cityId">Save {{ c.name }}</button>
              </div>
            </form>
          }
        </div>
      }
    }

    <!-- Service editor -->
    <pg-dialog [open]="serviceOpen()" [heading]="serviceForm.id ? 'Edit service' : 'New service'" size="lg" (closed)="serviceOpen.set(false)">
      <form class="grid gap-4 sm:grid-cols-2" (ngSubmit)="saveService()" #sf="ngForm">
        <div class="sm:col-span-2">
          <label class="label" for="sv-name">Name</label>
          <input id="sv-name" name="name" class="field" required maxlength="100" [(ngModel)]="serviceForm.name" (ngModelChange)="!serviceForm.id && (serviceForm.slug = slugify($event))" />
        </div>
        <div>
          <label class="label" for="sv-cat">Category</label>
          <select id="sv-cat" name="cat" class="field" required [(ngModel)]="serviceForm.categoryId">
            @for (c of categories.value() ?? []; track c.id) {
              <option [ngValue]="c.id">{{ c.name }}</option>
            }
          </select>
        </div>
        <div>
          <label class="label" for="sv-slug">URL slug</label>
          <input id="sv-slug" name="slug" class="field" required pattern="[a-z0-9-]+" maxlength="80" [(ngModel)]="serviceForm.slug" />
        </div>
        <div>
          <label class="label" for="sv-price">Base price (₹)</label>
          <input id="sv-price" name="price" type="number" min="1" class="field" required [(ngModel)]="serviceForm.basePrice" />
        </div>
        <div>
          <label class="label" for="sv-dur">Duration (minutes)</label>
          <input id="sv-dur" name="dur" type="number" min="15" step="15" class="field" required [(ngModel)]="serviceForm.durationMinutes" />
        </div>
        <div class="sm:col-span-2">
          <label class="label" for="sv-desc">Description</label>
          <textarea id="sv-desc" name="desc" class="field min-h-20" maxlength="500" [(ngModel)]="serviceForm.description"></textarea>
        </div>
        <label class="flex items-center gap-3 text-sm sm:col-span-2">
          <input type="checkbox" name="active" class="size-5 rounded accent-brand" [(ngModel)]="serviceForm.active" /> Visible to customers
        </label>
        <div class="flex flex-col-reverse gap-2 sm:col-span-2 sm:flex-row sm:justify-end">
          <button type="button" class="btn-ghost" (click)="serviceOpen.set(false)">Cancel</button>
          <button type="submit" class="btn-primary" [disabled]="saving() || !sf.valid">
            @if (saving()) {
              <pg-spinner />
            }
            Save service
          </button>
        </div>
      </form>
    </pg-dialog>

    <!-- Category editor -->
    <pg-dialog [open]="categoryOpen()" [heading]="categoryForm.id ? 'Edit category' : 'New category'" size="lg" (closed)="categoryOpen.set(false)">
      <form class="grid gap-4 sm:grid-cols-2" (ngSubmit)="saveCategory()" #cf="ngForm">
        <div>
          <label class="label" for="ct-name">Name</label>
          <input id="ct-name" name="name" class="field" required maxlength="80" [(ngModel)]="categoryForm.name" (ngModelChange)="!categoryForm.id && (categoryForm.slug = slugify($event))" />
        </div>
        <div>
          <label class="label" for="ct-slug">URL slug</label>
          <input id="ct-slug" name="slug" class="field" required pattern="[a-z0-9-]+" maxlength="60" [(ngModel)]="categoryForm.slug" />
        </div>
        <div>
          <label class="label" for="ct-icon">Icon name</label>
          <input id="ct-icon" name="icon" class="field" maxlength="40" [(ngModel)]="categoryForm.icon" placeholder="zap, droplets, hammer…" />
        </div>
        <div>
          <label class="label" for="ct-order">Sort order</label>
          <input id="ct-order" name="order" type="number" class="field" [(ngModel)]="categoryForm.sortOrder" />
        </div>
        <div class="sm:col-span-2">
          <label class="label" for="ct-img">Image path</label>
          <input id="ct-img" name="img" class="field" maxlength="200" [(ngModel)]="categoryForm.imageUrl" placeholder="/images/categories/electrician.webp" />
        </div>
        <div class="sm:col-span-2">
          <label class="label" for="ct-desc">Description</label>
          <textarea id="ct-desc" name="desc" class="field min-h-20" maxlength="300" [(ngModel)]="categoryForm.description"></textarea>
        </div>
        <label class="flex items-center gap-3 text-sm sm:col-span-2">
          <input type="checkbox" name="active" class="size-5 rounded accent-brand" [(ngModel)]="categoryForm.active" /> Visible to customers
        </label>
        <div class="flex flex-col-reverse gap-2 sm:col-span-2 sm:flex-row sm:justify-end">
          <button type="button" class="btn-ghost" (click)="categoryOpen.set(false)">Cancel</button>
          <button type="submit" class="btn-primary" [disabled]="saving() || !cf.valid">Save category</button>
        </div>
      </form>
    </pg-dialog>
  `,
})
export default class AdminCatalogPage {
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);

  protected readonly tab = signal<Tab>('services');
  protected readonly tabs: TabItem<Tab>[] = [
    { value: 'services', label: 'Services' },
    { value: 'categories', label: 'Categories' },
    { value: 'pricing', label: 'City pricing' },
  ];

  protected readonly categoryFilter = signal<number | null>(null);
  protected readonly categories = httpResource<AdminCategory[]>(() => `${API}/admin/categories`);
  protected readonly services = httpResource<AdminService[]>(() => `${API}/admin/services${query({ categoryId: this.categoryFilter() })}`);
  protected readonly cities = httpResource<CityPricing[]>(() => (this.tab() === 'pricing' ? `${API}/admin/cities` : undefined));

  protected readonly saving = signal(false);
  protected readonly savingCity = signal<number | null>(null);
  protected readonly serviceOpen = signal(false);
  protected readonly categoryOpen = signal(false);
  protected serviceForm = this.blankService();
  protected categoryForm = this.blankCategory();
  protected readonly icon = categoryIcon;
  protected readonly slugify = slugify;
  protected readonly firstCategory = computed(() => this.categories.value()?.[0]?.id ?? null);

  private blankService() {
    return { id: null as number | null, categoryId: null as number | null, slug: '', name: '', description: '', basePrice: 299, durationMinutes: 60, active: true };
  }

  private blankCategory() {
    return { id: null as number | null, slug: '', name: '', description: '', icon: '', imageUrl: '', sortOrder: 100, active: true };
  }

  protected editService(s: AdminService | null): void {
    this.serviceForm = s
      ? { id: s.id, categoryId: s.categoryId, slug: s.slug, name: s.name, description: s.description ?? '', basePrice: s.basePrice, durationMinutes: s.durationMinutes, active: s.active }
      : { ...this.blankService(), categoryId: this.categoryFilter() ?? this.firstCategory() };
    this.serviceOpen.set(true);
  }

  protected editCategory(c: AdminCategory | null): void {
    this.categoryForm = c
      ? { id: c.id, slug: c.slug, name: c.name, description: c.description ?? '', icon: c.icon ?? '', imageUrl: c.imageUrl ?? '', sortOrder: c.sortOrder, active: c.active }
      : this.blankCategory();
    this.categoryOpen.set(true);
  }

  protected saveService(): void {
    const f = this.serviceForm;
    if (f.categoryId === null) return;
    this.saving.set(true);
    this.api
      .saveService(f.id, {
        categoryId: f.categoryId,
        slug: f.slug.trim(),
        name: f.name.trim(),
        description: f.description.trim() || null,
        basePrice: Number(f.basePrice),
        durationMinutes: Number(f.durationMinutes),
        active: f.active,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.serviceOpen.set(false);
          this.toast.success('Service saved');
          this.services.reload();
          this.categories.reload();
        },
        error: (err) => {
          this.saving.set(false);
          this.toast.error(errorMessage(err));
        },
      });
  }

  protected saveCategory(): void {
    const f = this.categoryForm;
    this.saving.set(true);
    this.api
      .saveCategory(f.id, {
        slug: f.slug.trim(),
        name: f.name.trim(),
        description: f.description.trim() || null,
        icon: f.icon.trim() || null,
        imageUrl: f.imageUrl.trim() || null,
        sortOrder: Number(f.sortOrder),
        active: f.active,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.categoryOpen.set(false);
          this.toast.success('Category saved');
          this.categories.reload();
        },
        error: (err) => {
          this.saving.set(false);
          this.toast.error(errorMessage(err));
        },
      });
  }

  /** The API stores the rate as a fraction (0.05); admins edit a percentage (5). */
  protected percent(c: CityPricing): number {
    return Math.round(Number(c.commissionRate) * 10000) / 100;
  }

  protected example(c: CityPricing): number {
    const d = 6;
    if (d <= Number(c.travelFreeKm)) return 0;
    return Math.min(Number(c.travelBaseFee) + Number(c.travelPerKm) * (d - Number(c.travelFreeKm)), Number(c.travelFeeCap));
  }

  protected savePricing(c: CityPricing): void {
    this.savingCity.set(c.cityId);
    this.api
      .savePricing(c.cityId, {
        commissionRate: Number(c.commissionRate),
        travelFreeKm: Number(c.travelFreeKm),
        travelBaseFee: Number(c.travelBaseFee),
        travelPerKm: Number(c.travelPerKm),
        travelFeeCap: Number(c.travelFeeCap),
      })
      .subscribe({
        next: () => {
          this.savingCity.set(null);
          this.toast.success(`${c.name} pricing saved. New quotes use it immediately.`);
        },
        error: (err) => {
          this.savingCity.set(null);
          this.toast.error(errorMessage(err));
          this.cities.reload();
        },
      });
  }

  protected readonly Pencil = Pencil;
  protected readonly Plus = Plus;
}
