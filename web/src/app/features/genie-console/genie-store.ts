import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';

import { API } from '../../core/api/api';
import { BookingSummary, MyProfile, OnboardingStep, Page } from '../../core/api/models';

/** Human text for each onboarding step the API may report as missing. */
export const STEP_TEXT: Record<OnboardingStep, { title: string; hint: string; section: 'profile' | 'services' | 'availability' | 'documents' }> = {
  PROFILE: { title: 'About you', hint: 'A short bio and your years of experience', section: 'profile' },
  LOCATION: { title: 'Base location', hint: 'Where you start from, and how far you travel', section: 'profile' },
  PAYOUT: { title: 'Payout UPI ID', hint: 'Where we send your weekly earnings', section: 'profile' },
  SERVICES: { title: 'Services & prices', hint: 'At least one service you offer', section: 'services' },
  AVAILABILITY: { title: 'Working hours', hint: 'When customers can book you', section: 'availability' },
  ID_DOCUMENT: { title: 'ID proof', hint: 'Aadhaar or PAN card photo or PDF', section: 'documents' },
  SELFIE: { title: 'Selfie', hint: 'A clear photo of your face', section: 'documents' },
};

export const ALL_STEPS: OnboardingStep[] = ['PROFILE', 'LOCATION', 'PAYOUT', 'SERVICES', 'AVAILABILITY', 'ID_DOCUMENT', 'SELFIE'];

/** The signed-in Genie's profile and pending-request count, shared by every console page. */
@Injectable({ providedIn: 'root' })
export class GenieStore {
  private readonly http = inject(HttpClient);

  readonly profile = signal<MyProfile | null>(null);
  readonly requests = signal(0);
  readonly loading = signal(false);

  readonly status = computed(() => this.profile()?.verificationStatus ?? null);
  readonly approved = computed(() => this.status() === 'APPROVED');
  /** Onboarding can be edited while registering or when changes were requested. */
  readonly editable = computed(() => ['REGISTERED', 'NEEDS_CHANGES', 'APPROVED'].includes(this.status() ?? ''));
  readonly progress = computed(() => {
    const missing = this.profile()?.missingSteps.length ?? ALL_STEPS.length;
    return Math.round(((ALL_STEPS.length - missing) / ALL_STEPS.length) * 100);
  });

  load(): void {
    this.loading.set(true);
    this.http.get<MyProfile>(`${API}/genie/profile`).subscribe({
      next: (p) => {
        this.profile.set(p);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
    this.refreshRequests();
  }

  set(profile: MyProfile): void {
    this.profile.set(profile);
  }

  refreshRequests(): void {
    this.http.get<Page<BookingSummary>>(`${API}/genie/bookings`, { params: { scope: 'REQUESTS', size: 1 } }).subscribe({
      next: (page) => this.requests.set(page.total),
      error: () => undefined,
    });
  }
}
