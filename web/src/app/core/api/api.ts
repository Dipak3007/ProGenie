import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CategoryDetail,
  ContactRequest,
  DayCount,
  DaySlots,
  GenieDetail,
  GenieSort,
  Page,
  PriceBreakdown,
  ProblemDetail,
  Review,
  SearchHit,
} from './models';

/** Relative base URL: the dev proxy (ng serve) and Nginx (Docker) both forward /api to Spring Boot. */
export const API = '/api/v1';

/** Public catalog commands and parameterised reads. Simple list reads use httpResource() directly in components. */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);

  categoryDetail(slug: string): Observable<CategoryDetail> {
    return this.http.get<CategoryDetail>(`${API}/categories/${encodeURIComponent(slug)}`);
  }

  genieDetail(id: string): Observable<GenieDetail> {
    return this.http.get<GenieDetail>(`${API}/genies/${encodeURIComponent(id)}`);
  }

  genieReviews(id: string, page = 0, size = 10): Observable<Page<Review>> {
    return this.http.get<Page<Review>>(`${API}/genies/${encodeURIComponent(id)}/reviews`, { params: { page, size } });
  }

  estimate(genieId: string, serviceId: number, lat: number, lng: number, tip = 0): Observable<PriceBreakdown> {
    const params = new HttpParams()
      .set('genieId', genieId)
      .set('serviceId', serviceId)
      .set('lat', lat)
      .set('lng', lng)
      .set('tip', tip);
    return this.http.get<PriceBreakdown>(`${API}/pricing/estimate`, { params });
  }

  slotDays(genieId: string, serviceId: number, days = 14): Observable<DayCount[]> {
    return this.http.get<DayCount[]>(`${API}/genies/${encodeURIComponent(genieId)}/slot-days`, { params: { serviceId, days } });
  }

  slots(genieId: string, serviceId: number, date: string): Observable<DaySlots> {
    return this.http.get<DaySlots>(`${API}/genies/${encodeURIComponent(genieId)}/slots`, { params: { serviceId, date } });
  }

  search(q: string, limit = 8): Observable<SearchHit[]> {
    return this.http.get<SearchHit[]>(`${API}/search`, { params: { q, limit } });
  }

  sendContact(body: ContactRequest): Observable<void> {
    return this.http.post<void>(`${API}/contact`, body);
  }
}

export function geniesUrl(category?: string, sort: GenieSort = 'RATING', limit = 12, serviceId?: number | null): string {
  const params = new HttpParams({
    fromObject: { sort, limit, ...(category ? { category } : {}), ...(serviceId ? { serviceId } : {}) },
  });
  return `${API}/genies?${params.toString()}`;
}

/** Builds "?a=1&b=2" from an object, skipping null/undefined/empty values. */
export function query(params: Record<string, string | number | boolean | null | undefined>): string {
  const clean: Record<string, string> = {};
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== '') clean[key] = String(value);
  }
  const text = new HttpParams({ fromObject: clean }).toString();
  return text ? `?${text}` : '';
}

/** The API's machine-readable error code (e.g. SLOT_TAKEN), if any. */
export function errorCode(err: unknown): string | null {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as ProblemDetail | null;
    return body?.code ?? body?.title ?? null;
  }
  return null;
}

/** Turns an HttpErrorResponse into a human message (uses the API's ProblemDetail when present). */
export function errorMessage(err: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return 'Cannot reach the server. Is the API running on port 8080?';
    }
    const body = err.error as ProblemDetail | null;
    if (body?.errors) {
      return Object.entries(body.errors)
        .map(([field, msg]) => `${field}: ${msg}`)
        .join(' · ');
    }
    return body?.detail ?? fallback;
  }
  return fallback;
}
