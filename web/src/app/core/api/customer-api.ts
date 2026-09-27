import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API } from './api';
import {
  Address,
  AddressRequest,
  BookingDetail,
  CreateBookingRequest,
  Payment,
  PaymentOrder,
  Review,
  StartCode,
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
