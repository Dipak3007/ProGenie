import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { API } from './api';
import { HelpArticle, HelpCategory, TicketCategory } from './models';

/** Display names of the help centre's categories, in the order they are shown (Phase 3.5B). */
export const HELP_CATEGORY_LABEL: Record<HelpCategory, string> = {
  BOOKING: 'Booking',
  CANCELLATIONS: 'Cancellations',
  PAYMENTS: 'Payments & tips',
  GENIE_NOW: 'Genie Now',
  PROBLEMS: 'Problems with a job',
  ACCOUNT: 'Account & privacy',
  GENIES: 'For Genies',
};

/** The public help centre (Phase 3.5B). */
@Injectable({ providedIn: 'root' })
export class HelpApi {
  private readonly http = inject(HttpClient);

  /** Up to 3 answers "Report a problem" shows for a complaint category. */
  suggestions(ticketCategory: TicketCategory): Observable<HelpArticle[]> {
    return this.http.get<HelpArticle[]>(`${API}/help/suggestions`, { params: { ticketCategory } });
  }

  vote(id: number, helpful: boolean): Observable<void> {
    return this.http.post<void>(`${API}/help/${id}/vote`, { helpful });
  }
}
