import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API } from './api';
import { LEGAL_SLUG } from './account-api';
import {
  AdminCategory,
  AdminGenieDetail,
  AdminService,
  BookingDetail,
  CategoryRequest,
  CityPricing,
  ContactMessage,
  Decision,
  LegalDocument,
  LegalDraftRequest,
  LegalKind,
  Payout,
  Refund,
  RefundRequest,
  ResolveTicketRequest,
  TicketDetail,
  PricingRequest,
  ServiceRequest,
  Wallet,
} from './models';

/** Back-office commands. Reads use httpResource() in the pages. */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API}/admin`;

  // --- genies
  decide(genieId: string, decision: Decision, note: string | null): Observable<AdminGenieDetail> {
    return this.http.post<AdminGenieDetail>(`${this.base}/genies/${genieId}/decision`, { decision, note });
  }

  clearFlag(genieId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/genies/${genieId}/clear-flag`, {});
  }

  reviewDocument(docId: string, approve: boolean, note: string | null): Observable<void> {
    return this.http.post<void>(`${this.base}/genie-documents/${docId}/review`, { approve, note });
  }

  documentFile(docId: string): Observable<Blob> {
    return this.http.get(`${this.base}/genie-documents/${docId}/file`, { responseType: 'blob' });
  }

  settle(genieId: string, amount: number, reference: string): Observable<Wallet> {
    return this.http.post<Wallet>(`${this.base}/genies/${genieId}/settlements`, { amount, reference });
  }

  // --- bookings
  cancelBooking(id: string, reason: string, waiveFee: boolean): Observable<BookingDetail> {
    return this.http.post<BookingDetail>(`${this.base}/bookings/${id}/cancel`, { reason, waiveFee });
  }

  waiveFee(id: string): Observable<BookingDetail> {
    return this.http.post<BookingDetail>(`${this.base}/bookings/${id}/waive-fee`, {});
  }

  // --- users
  setUserStatus(id: string, status: 'ACTIVE' | 'SUSPENDED'): Observable<void> {
    return this.http.put<void>(`${this.base}/users/${id}/status`, { status });
  }

  // --- catalog
  saveCategory(id: number | null, body: CategoryRequest): Observable<AdminCategory> {
    return id === null
      ? this.http.post<AdminCategory>(`${this.base}/categories`, body)
      : this.http.put<AdminCategory>(`${this.base}/categories/${id}`, body);
  }

  saveService(id: number | null, body: ServiceRequest): Observable<AdminService> {
    return id === null
      ? this.http.post<AdminService>(`${this.base}/services`, body)
      : this.http.put<AdminService>(`${this.base}/services/${id}`, body);
  }

  savePricing(cityId: number, body: PricingRequest): Observable<CityPricing> {
    return this.http.put<CityPricing>(`${this.base}/cities/${cityId}/pricing`, body);
  }

  // --- finance
  generatePayouts(periodEnd: string | null): Observable<{ created: number }> {
    return this.http.post<{ created: number }>(`${this.base}/payouts/generate`, periodEnd ? { periodEnd } : {});
  }

  markPaid(id: string, reference: string): Observable<Payout> {
    return this.http.post<Payout>(`${this.base}/payouts/${id}/mark-paid`, { reference });
  }

  markFailed(id: string, reason: string): Observable<Payout> {
    return this.http.post<Payout>(`${this.base}/payouts/${id}/mark-failed`, { reason });
  }

  // --- inbox
  setMessageStatus(id: number, status: string): Observable<ContactMessage> {
    return this.http.patch<ContactMessage>(`${this.base}/contact-messages/${id}`, { status });
  }

  // --- refunds
  refund(paymentId: string, body: RefundRequest): Observable<Refund> {
    return this.http.post<Refund>(`${this.base}/payments/${paymentId}/refunds`, body);
  }

  // --- complaints
  assignTicket(id: string, adminId: string | null = null): Observable<TicketDetail> {
    return this.http.post<TicketDetail>(`${this.base}/tickets/${id}/assign`, adminId ? { adminId } : {});
  }

  replyTicket(id: string, body: string, internal: boolean, awaitingReply: boolean): Observable<TicketDetail> {
    return this.http.post<TicketDetail>(`${this.base}/tickets/${id}/reply`, { body, internal, awaitingReply });
  }

  resolveTicket(id: string, body: ResolveTicketRequest): Observable<TicketDetail> {
    return this.http.post<TicketDetail>(`${this.base}/tickets/${id}/resolve`, body);
  }

  rejectTicket(id: string, reason: string): Observable<TicketDetail> {
    return this.http.post<TicketDetail>(`${this.base}/tickets/${id}/reject`, { reason });
  }

  // --- outgoing messages
  retryMessage(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/messages/${id}/retry`, {});
  }

  // --- legal
  saveLegalDraft(kind: LegalKind, body: LegalDraftRequest): Observable<LegalDocument> {
    return this.http.put<LegalDocument>(`${this.base}/legal/${LEGAL_SLUG[kind]}/draft`, body);
  }

  deleteLegalDraft(kind: LegalKind): Observable<void> {
    return this.http.delete<void>(`${this.base}/legal/${LEGAL_SLUG[kind]}/draft`);
  }

  publishLegal(id: string): Observable<LegalDocument> {
    return this.http.post<LegalDocument>(`${this.base}/legal/${id}/publish`, {});
  }
}
