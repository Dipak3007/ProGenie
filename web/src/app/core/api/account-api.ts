import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API } from './api';
import {
  CompanyInfo,
  Consent,
  DeletionStatus,
  ExportJob,
  LegalDocument,
  LegalKind,
  LegalVersion,
  NotificationPreference,
  OtpChallenge,
  OtpPurpose,
  OtpVerifyResponse,
} from './models';

/** URL slug for each policy document (/legal/terms, …). */
export const LEGAL_SLUG: Record<LegalKind, string> = {
  TERMS: 'terms',
  PRIVACY: 'privacy',
  REFUNDS: 'refunds',
  GENIE_AGREEMENT: 'genie-agreement',
};

export const LEGAL_TITLE: Record<LegalKind, string> = {
  TERMS: 'Terms of Service',
  PRIVACY: 'Privacy Policy',
  REFUNDS: 'Cancellation & Refund Policy',
  GENIE_AGREEMENT: 'Genie Partner Agreement',
};

/**
 * Account features shared by every role: one-time codes, password reset, policies and consent,
 * message preferences, data export and account deletion.
 */
@Injectable({ providedIn: 'root' })
export class AccountApi {
  private readonly http = inject(HttpClient);

  // --- one-time codes
  requestOtp(purpose: OtpPurpose, identifier?: string | null): Observable<OtpChallenge> {
    return this.http.post<OtpChallenge>(`${API}/auth/otp/request`, { purpose, identifier: identifier ?? null });
  }

  /** withCredentials: a LOGIN code sets the refresh cookie. */
  verifyOtp(challengeId: string, code: string): Observable<OtpVerifyResponse> {
    return this.http.post<OtpVerifyResponse>(`${API}/auth/otp/verify`, { challengeId, code }, { withCredentials: true });
  }

  resetPassword(resetToken: string, newPassword: string): Observable<void> {
    return this.http.post<void>(`${API}/auth/password/reset`, { resetToken, newPassword });
  }

  // --- policies (public) and consent
  legal(kind: LegalKind): Observable<LegalDocument> {
    return this.http.get<LegalDocument>(`${API}/legal/${LEGAL_SLUG[kind]}`);
  }

  legalVersions(kind: LegalKind): Observable<LegalVersion[]> {
    return this.http.get<LegalVersion[]>(`${API}/legal/${LEGAL_SLUG[kind]}/versions`);
  }

  legalVersion(kind: LegalKind, version: number): Observable<LegalDocument> {
    return this.http.get<LegalDocument>(`${API}/legal/${LEGAL_SLUG[kind]}/versions/${version}`);
  }

  company(): Observable<CompanyInfo> {
    return this.http.get<CompanyInfo>(`${API}/legal/company`);
  }

  consents(): Observable<Consent[]> {
    return this.http.get<Consent[]>(`${API}/me/consents`);
  }

  acceptConsents(kinds: LegalKind[]): Observable<{ pendingConsents: LegalKind[] }> {
    return this.http.post<{ pendingConsents: LegalKind[] }>(`${API}/me/consents`, { kinds });
  }

  // --- message preferences
  preferences(): Observable<NotificationPreference[]> {
    return this.http.get<NotificationPreference[]>(`${API}/me/notification-preferences`);
  }

  savePreferences(prefs: NotificationPreference[]): Observable<NotificationPreference[]> {
    return this.http.put<NotificationPreference[]>(`${API}/me/notification-preferences`, prefs);
  }

  // --- privacy
  exports(): Observable<ExportJob[]> {
    return this.http.get<ExportJob[]>(`${API}/me/data-export`);
  }

  requestExport(): Observable<ExportJob> {
    return this.http.post<ExportJob>(`${API}/me/data-export`, {});
  }

  exportFile(id: string): Observable<Blob> {
    return this.http.get(`${API}/me/data-export/${id}/file`, { responseType: 'blob' });
  }

  deletionStatus(): Observable<DeletionStatus> {
    return this.http.get<DeletionStatus>(`${API}/me/deletion-request`);
  }

  requestDeletion(reason: string | null): Observable<DeletionStatus> {
    return this.http.post<DeletionStatus>(`${API}/me/deletion-request`, { reason });
  }

  cancelDeletion(): Observable<void> {
    return this.http.delete<void>(`${API}/me/deletion-request`);
  }
}
