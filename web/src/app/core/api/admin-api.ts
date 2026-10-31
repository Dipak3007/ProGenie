import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API } from './api';
import { LEGAL_SLUG } from './account-api';
import {
  AdminCategory,
  AdminCity,
  AdminCoupon,
  AdminGenieDetail,
  AdminPincode,
  AdminService,
  BookingDetail,
  CategoryRequest,
  CityRequest,
  ContactMessage,
  CouponRedemption,
  CouponRequest,
  Decision,
  DemandLayer,
  DemandMap,
  FileLink,
  LegalDocument,
  LegalDraftRequest,
  LegalKind,
  Page,
  Payout,
  PincodeRequest,
  PricingRequest,
  Refund,
  RefundRequest,
  ResolveTicketRequest,
  ServiceRequest,
  TicketDetail,
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

  documentLink(docId: string): Observable<FileLink> {
    return this.http.get<FileLink>(`${this.base}/genie-documents/${docId}/link`);
  }

  // --- coupons (Phase 3B)
  coupons(status?: string, q?: string): Observable<AdminCoupon[]> {
    const params: Record<string, string> = {};
    if (status) params['status'] = status;
    if (q) params['q'] = q;
    return this.http.get<AdminCoupon[]>(`${this.base}/coupons`, { params });
  }

  createCoupon(body: CouponRequest): Observable<AdminCoupon> {
    return this.http.post<AdminCoupon>(`${this.base}/coupons`, body);
  }

  updateCoupon(id: number, body: CouponRequest): Observable<AdminCoupon> {
    return this.http.put<AdminCoupon>(`${this.base}/coupons/${id}`, body);
  }

  setCouponActive(id: number, active: boolean): Observable<AdminCoupon> {
    return this.http.put<AdminCoupon>(`${this.base}/coupons/${id}/status`, { active });
  }

  couponRedemptions(id: number, page = 0): Observable<Page<CouponRedemption>> {
    return this.http.get<Page<CouponRedemption>>(`${this.base}/coupons/${id}/redemptions`, { params: { page, size: 20 } });
  }

  /** A Genie's monthly earnings statement (month "2026-10") as a file to save. */
  genieStatement(genieId: string, month: string, format: 'csv' | 'pdf'): Observable<Blob> {
    return this.http.get(`${this.base}/genies/${genieId}/statements`, { params: { month, format }, responseType: 'blob' });
  }

  /** Recalculates every Genie's badges now (they are also recalculated every night). */
  recalculateBadges(): Observable<{ genies: number }> {
    return this.http.post<{ genies: number }>(`${this.base}/genies/badges/recalculate`, {});
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

  savePricing(cityId: number, body: PricingRequest): Observable<AdminCity> {
    return this.http.put<AdminCity>(`${this.base}/cities/${cityId}/pricing`, body);
  }

  // --- cities: opening one is data only (create → PIN codes → pricing → switch on)
  saveCity(id: number | null, body: CityRequest): Observable<AdminCity> {
    return id === null
      ? this.http.post<AdminCity>(`${this.base}/cities`, body)
      : this.http.put<AdminCity>(`${this.base}/cities/${id}`, body);
  }

  setCityActive(id: number, active: boolean): Observable<AdminCity> {
    return this.http.put<AdminCity>(`${this.base}/cities/${id}/status`, { active });
  }

  pincodes(cityId: number): Observable<AdminPincode[]> {
    return this.http.get<AdminPincode[]>(`${this.base}/cities/${cityId}/pincodes`);
  }

  savePincode(cityId: number, pincode: string, body: PincodeRequest): Observable<AdminPincode> {
    return this.http.put<AdminPincode>(`${this.base}/cities/${cityId}/pincodes/${encodeURIComponent(pincode)}`, body);
  }

  deletePincode(cityId: number, pincode: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/cities/${cityId}/pincodes/${encodeURIComponent(pincode)}`);
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

  /** Demand heatmap (Phase 3D): bookings or unmet demand by PIN code. */
  demand(f: { layer: DemandLayer; days: number; cityId: number | null; categoryId: number | null; weekday: number | null; hour: number | null }): Observable<DemandMap> {
    const params: Record<string, string | number> = { layer: f.layer, days: f.days };
    if (f.cityId) params['cityId'] = f.cityId;
    if (f.categoryId) params['categoryId'] = f.categoryId;
    if (f.weekday) params['weekday'] = f.weekday;
    if (f.hour !== null) params['hour'] = f.hour;
    return this.http.get<DemandMap>(`${this.base}/demand`, { params });
  }
}
