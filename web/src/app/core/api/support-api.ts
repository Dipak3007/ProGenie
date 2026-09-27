import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API } from './api';
import { RaiseTicketRequest, Role, TicketAttachment, TicketDetail } from './models';

/** "Report a problem" and privacy requests, for customers, Genies and (for photos) admins. */
@Injectable({ providedIn: 'root' })
export class SupportApi {
  private readonly http = inject(HttpClient);

  raise(role: Role, bookingId: string, body: RaiseTicketRequest): Observable<TicketDetail> {
    const base = role === 'GENIE' ? `${API}/genie/bookings` : `${API}/bookings`;
    return this.http.post<TicketDetail>(`${base}/${bookingId}/tickets`, body);
  }

  privacyRequest(subject: string, description: string): Observable<TicketDetail> {
    return this.http.post<TicketDetail>(`${API}/tickets/privacy`, { subject, description });
  }

  get(id: string): Observable<TicketDetail> {
    return this.http.get<TicketDetail>(`${API}/tickets/${id}`);
  }

  reply(id: string, body: string): Observable<TicketDetail> {
    return this.http.post<TicketDetail>(`${API}/tickets/${id}/messages`, { body });
  }

  addPhoto(id: string, file: File): Observable<TicketAttachment> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<TicketAttachment>(`${API}/tickets/${id}/attachments`, form);
  }

  photo(id: string, attachmentId: string): Observable<Blob> {
    return this.http.get(`${API}/tickets/${id}/attachments/${attachmentId}`, { responseType: 'blob' });
  }

  accept(id: string): Observable<TicketDetail> {
    return this.http.post<TicketDetail>(`${API}/tickets/${id}/accept`, {});
  }

  reopen(id: string, body: string): Observable<TicketDetail> {
    return this.http.post<TicketDetail>(`${API}/tickets/${id}/reopen`, { body });
  }

  receiptPdf(id: string): Observable<Blob> {
    return this.http.get(`${API}/receipts/${id}/pdf`, { responseType: 'blob' });
  }
}
