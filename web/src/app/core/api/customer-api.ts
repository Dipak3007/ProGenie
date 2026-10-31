import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API } from './api';
import {
  Address,
  AddressRequest,
  BookingDetail,
  CouponOffer,
  CreateBookingRequest,
  Payment,
  PaymentOrder,
  Review,
  StartCode,
  InstantQuote,
  InstantRequest,
  PaymentMethod,
  Tracking,
  User,
} from './models';

/** Customer commands: profile, addresses, favourites, bookings and payments. */
@Injectable({ providedIn: 'root' })
export class CustomerApi {
  private readonly http = inject(HttpClient);

  // --- profile
  updateProfile(body: { fullName: string; email: string | null }): Observable<User> {
    return this.http.patch<User>(`${API}/me`, body);
  }

  changePassword(body: { currentPassword: string; newPassword: string }): Observable<void> {
    return this.http.post<void>(`${API}/auth/change-password`, body, { withCredentials: true });
  }

  // --- addresses
  addresses(): Observable<Address[]> {
    return this.http.get<Address[]>(`${API}/me/addresses`);
  }

  createAddress(body: AddressRequest): Observable<Address> {
    return this.http.post<Address>(`${API}/me/addresses`, body);
  }

  updateAddress(id: string, body: AddressRequest): Observable<Address> {
    return this.http.put<Address>(`${API}/me/addresses/${id}`, body);
  }

  deleteAddress(id: string): Observable<void> {
    return this.http.delete<void>(`${API}/me/addresses/${id}`);
  }

  makeDefault(id: string): Observable<Address> {
    return this.http.post<Address>(`${API}/me/addresses/${id}/default`, {});
  }

  // --- favourites
  addFavourite(genieId: string): Observable<void> {
    return this.http.put<void>(`${API}/me/favourites/${genieId}`, {});
  }

  removeFavourite(genieId: string): Observable<void> {
    return this.http.delete<void>(`${API}/me/favourites/${genieId}`);
  }

  // --- bookings
  // --- coupons (Phase 3B)
  /** Public coupons for this checkout, each with its discount or why it can't be used. */
  /** Public coupons for an order. `genieId` null = Genie Now (the service's base price is used). */
  coupons(genieId: string | null, serviceId: number, addressId: string): Observable<CouponOffer[]> {
    const params: Record<string, string | number> = { serviceId, addressId };
    if (genieId) params['genieId'] = genieId;
    return this.http.get<CouponOffer[]>(`${API}/coupons`, { params });
  }

  /** A typed code: the offer, or 404 COUPON_NOT_FOUND / 422 COUPON_* with the reason. */
  checkCoupon(code: string, genieId: string | null, serviceId: number, addressId: string): Observable<CouponOffer> {
    return this.http.post<CouponOffer>(`${API}/coupons/check`, { code, genieId, serviceId, addressId });
  }

  createBooking(body: CreateBookingRequest, idempotencyKey: string): Observable<BookingDetail> {
    return this.http.post<BookingDetail>(`${API}/bookings`, body, { headers: { 'Idempotency-Key': idempotencyKey } });
  }

  cancel(id: string, reason: string | null): Observable<BookingDetail> {
    return this.http.post<BookingDetail>(`${API}/bookings/${id}/cancel`, { reason });
  }

  reschedule(id: string, slotStart: string): Observable<BookingDetail> {
    return this.http.post<BookingDetail>(`${API}/bookings/${id}/reschedule`, { slotStart });
  }

  changeTip(id: string, tipAmount: number): Observable<BookingDetail> {
    return this.http.put<BookingDetail>(`${API}/bookings/${id}/tip`, { tipAmount });
  }

  startCode(id: string): Observable<StartCode> {
    return this.http.post<StartCode>(`${API}/bookings/${id}/start-code`, {});
  }

  // --- Genie Now (Phase 3C-9)
  instantQuote(serviceId: number, addressId: string): Observable<InstantQuote> {
    return this.http.get<InstantQuote>(`${API}/instant/quote`, { params: { serviceId, addressId } });
  }

  startInstant(body: {
    serviceId: number;
    addressId: string;
    paymentMethod: PaymentMethod;
    tipAmount: number;
    notes: string | null;
    couponCode: string | null;
  }): Observable<InstantRequest> {
    return this.http.post<InstantRequest>(`${API}/instant/requests`, body);
  }

  instantRequest(id: string): Observable<InstantRequest> {
    return this.http.get<InstantRequest>(`${API}/instant/requests/${id}`);
  }

  cancelInstant(id: string): Observable<InstantRequest> {
    return this.http.post<InstantRequest>(`${API}/instant/requests/${id}/cancel`, {});
  }

  /** The Genie's live position while on the way (also pushed as the live "tracking" event). */
  tracking(id: string): Observable<Tracking> {
    return this.http.get<Tracking>(`${API}/bookings/${id}/tracking`);
  }

  review(id: string, body: { rating: number; comment: string | null }): Observable<Review> {
    return this.http.post<Review>(`${API}/bookings/${id}/review`, body);
  }

  // --- payments
  paymentOrder(bookingId: string): Observable<PaymentOrder> {
    return this.http.post<PaymentOrder>(`${API}/bookings/${bookingId}/payment-order`, {});
  }

  payments(bookingId: string): Observable<Payment[]> {
    return this.http.get<Payment[]>(`${API}/bookings/${bookingId}/payments`);
  }

  confirmPayment(paymentId: string, body: { providerPaymentId: string; signature: string }): Observable<Payment> {
    return this.http.post<Payment>(`${API}/payments/${paymentId}/confirm`, body);
  }

  /** Local fake gateway only: completes the checkout on the server with a signed result. */
  simulatePayment(paymentId: string, outcome: 'success' | 'failure'): Observable<Payment> {
    return this.http.post<Payment>(`${API}/payments/${paymentId}/simulate`, {}, { params: { outcome } });
  }
}
