import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API } from './api';
import {
  AvailabilityWindow,
  BookingDetail,
  DocType,
  GenieDocument,
  MyProfile,
  MyService,
  ProfileRequest,
  Review,
  TimeOff,
} from './models';

/** Genie self-service commands: onboarding, availability, jobs and reviews. */
@Injectable({ providedIn: 'root' })
export class GenieApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API}/genie`;

  profile(): Observable<MyProfile> {
    return this.http.get<MyProfile>(`${this.base}/profile`);
  }

  updateProfile(body: ProfileRequest): Observable<MyProfile> {
    return this.http.put<MyProfile>(`${this.base}/profile`, body);
  }

  submit(): Observable<MyProfile> {
    return this.http.post<MyProfile>(`${this.base}/profile/submit`, {});
  }

  saveServices(services: { serviceId: number; priceOverride: number | null }[]): Observable<MyService[]> {
    return this.http.put<MyService[]>(`${this.base}/services`, { services });
  }

  saveAvailability(windows: AvailabilityWindow[]): Observable<AvailabilityWindow[]> {
    return this.http.put<AvailabilityWindow[]>(`${this.base}/availability`, { windows });
  }

  timeOff(): Observable<TimeOff[]> {
    return this.http.get<TimeOff[]>(`${this.base}/time-off`);
  }

  addTimeOff(body: { startsAt: string; endsAt: string; reason: string | null }): Observable<TimeOff> {
    return this.http.post<TimeOff>(`${this.base}/time-off`, body);
  }

  deleteTimeOff(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/time-off/${id}`);
  }

  uploadDocument(docType: DocType, last4: string | null, file: File): Observable<GenieDocument> {
    const form = new FormData();
    form.append('file', file);
    const params: Record<string, string> = { docType };
    if (last4) params['last4'] = last4;
    return this.http.post<GenieDocument>(`${this.base}/documents`, form, { params });
  }

  deleteDocument(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/documents/${id}`);
  }

  documentFile(id: string): Observable<Blob> {
    return this.http.get(`${this.base}/documents/${id}/file`, { responseType: 'blob' });
  }

  setOnline(online: boolean): Observable<{ online: boolean }> {
    return this.http.post<{ online: boolean }>(`${this.base}/status`, { online });
  }

  updateLocation(lat: number, lng: number, accuracyM: number | null): Observable<void> {
    return this.http.put<void>(`${this.base}/location`, { lat, lng, accuracyM });
  }

  // --- jobs
  accept(id: string): Observable<BookingDetail> {
    return this.http.post<BookingDetail>(`${this.base}/bookings/${id}/accept`, {});
  }

  decline(id: string, reason: string): Observable<BookingDetail> {
    return this.http.post<BookingDetail>(`${this.base}/bookings/${id}/decline`, { reason });
  }

  cancel(id: string, reason: string | null): Observable<BookingDetail> {
    return this.http.post<BookingDetail>(`${this.base}/bookings/${id}/cancel`, { reason });
  }

  start(id: string, code: string): Observable<BookingDetail> {
    return this.http.post<BookingDetail>(`${this.base}/bookings/${id}/start`, { code });
  }

  complete(id: string, body: { extraAmount: number | null; extraNote: string | null; cashCollected: boolean }): Observable<BookingDetail> {
    return this.http.post<BookingDetail>(`${this.base}/bookings/${id}/complete`, body);
  }

  reply(reviewId: string, reply: string): Observable<Review> {
    return this.http.post<Review>(`${this.base}/reviews/${reviewId}/reply`, { reply });
  }
}
