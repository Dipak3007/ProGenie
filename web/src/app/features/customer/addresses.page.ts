import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import {
  LucideDynamicIcon,
  LucideMapPin as MapPin,
  LucidePencil as Pencil,
  LucidePlus as Plus,
  LucideTrash2 as Trash,
} from '@lucide/angular';

import { API, errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { Address } from '../../core/api/models';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { mapsLink } from '../../shared/format';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, PageHead, Skeleton } from '../../shared/ui/kit';
import { AddressForm } from './address-form';

@Component({
  selector: 'pg-addresses-page',
  imports: [LucideDynamicIcon, AddressForm, Dialog, EmptyState, PageHead, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Saved addresses" subtitle="Up to 10 places. The map pin sets the travel fee and guides your Genie.">
      <button type="button" class="btn-primary" (click)="edit(null)">
        <svg [lucideIcon]="Plus" [size]="18" aria-hidden="true"></svg> Add address
      </button>
    </pg-page-head>

    <div class="mt-6">
      @if (list.value(); as addresses) {
        <div class="grid gap-4 md:grid-cols-2">
          @for (a of addresses; track a.id) {
            <article class="card flex flex-col p-5">
              <div class="flex items-start gap-3">
                <span class="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-mist text-brand-600">
                  <svg [lucideIcon]="MapPin" [size]="20" aria-hidden="true"></svg>
                </span>
                <div class="min-w-0 flex-1">
                  <p class="flex flex-wrap items-center gap-2 font-semibold">
                    {{ a.label }}
                    @if (a.isDefault) {
                      <span class="chip">Default</span>
                    }
                  </p>
                  <p class="mt-1 text-sm text-ink/80">{{ a.line1 }}{{ a.line2 ? ', ' + a.line2 : '' }}</p>
                  <p class="text-sm text-muted">{{ a.landmark ? a.landmark + ' · ' : '' }}{{ a.area ? a.area + ', ' : '' }}{{ a.cityName }} {{ a.pincode }}</p>
                  <a [href]="mapUrl(a)" target="_blank" rel="noopener" class="mt-1 inline-block text-xs font-semibold text-brand hover:underline">View on map</a>
                </div>
              </div>
              <div class="mt-4 flex flex-wrap gap-2 border-t border-line pt-4">
                <button type="button" class="btn-ghost min-h-10 px-4" (click)="edit(a)">
                  <svg [lucideIcon]="Pencil" [size]="16" aria-hidden="true"></svg> Edit
                </button>
                @if (!a.isDefault) {
                  <button type="button" class="btn-ghost min-h-10 px-4" (click)="makeDefault(a)">Make default</button>
                }
                <button type="button" class="btn ml-auto min-h-10 px-3 text-rose-700 hover:bg-rose-50" [attr.aria-label]="'Delete ' + a.label" (click)="remove(a)">
                  <svg [lucideIcon]="Trash" [size]="16" aria-hidden="true"></svg>
                </button>
              </div>
            </article>
          } @empty {
            <div class="md:col-span-2">
              <pg-empty [icon]="MapPin" title="No saved addresses" message="Add your home or office once and book in seconds next time.">
                <button type="button" class="btn-primary" (click)="edit(null)">Add address</button>
              </pg-empty>
            </div>
          }
        </div>
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="2" />
      }
    </div>

    <pg-dialog [open]="open()" [heading]="editing() ? 'Edit address' : 'New address'" size="lg" (closed)="open.set(false)">
      @if (open()) {
        <pg-address-form [address]="editing()" (saved)="onSaved()" (cancelled)="open.set(false)" />
      }
    </pg-dialog>
  `,
})
export default class AddressesPage {
  private readonly api = inject(CustomerApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly list = httpResource<Address[]>(() => `${API}/me/addresses`);
  protected readonly open = signal(false);
  protected readonly editing = signal<Address | null>(null);

  protected mapUrl(a: Address): string {
    return `https://www.google.com/maps/search/?api=1&query=${a.lat},${a.lng}`;
  }

  protected edit(a: Address | null): void {
    this.editing.set(a);
    this.open.set(true);
  }

  protected onSaved(): void {
    this.open.set(false);
    this.list.reload();
  }

  protected makeDefault(a: Address): void {
    this.api.makeDefault(a.id).subscribe({
      next: () => {
        this.toast.success(`${a.label} is now your default address`);
        this.list.reload();
      },
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }

  protected async remove(a: Address): Promise<void> {
    const { confirmed } = await this.confirm.ask({
      title: `Delete "${a.label}"?`,
      message: 'Past bookings keep their copy of the address.',
      confirmLabel: 'Delete',
      tone: 'danger',
    });
    if (!confirmed) return;
    this.api.deleteAddress(a.id).subscribe({
      next: () => {
        this.toast.success('Address deleted');
        this.list.reload();
      },
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }

  protected readonly MapPin = MapPin;
  protected readonly Plus = Plus;
  protected readonly Pencil = Pencil;
  protected readonly Trash = Trash;
  protected readonly mapsLink = mapsLink;
}
