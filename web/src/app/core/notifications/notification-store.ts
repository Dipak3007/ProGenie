import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';

import { API } from '../api/api';
import { AppNotification, Page } from '../api/models';
import { AuthStore } from '../auth/auth-store';

const POLL_MS = 45_000;

/**
 * Unread count for the bell, polled while someone is logged in (and refreshed when the tab regains focus).
 * A push channel (SSE/WebSocket) can replace polling later without touching the components.
 */
@Injectable({ providedIn: 'root' })
export class NotificationStore {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);

  readonly unread = signal(0);
  readonly latest = signal<AppNotification[]>([]);
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    const onFocus = () => this.auth.isLoggedIn() && this.refreshCount();
    window.addEventListener('focus', onFocus);
    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('focus', onFocus);
      this.stop();
    });

    effect(() => {
      if (this.auth.isLoggedIn()) {
        this.refreshCount();
        this.timer ??= setInterval(() => this.refreshCount(), POLL_MS);
      } else {
        this.stop();
        this.unread.set(0);
        this.latest.set([]);
      }
    });
  }

  refreshCount(): void {
    this.http.get<{ unread: number }>(`${API}/notifications/unread-count`).subscribe({
      next: (res) => this.unread.set(res.unread),
      error: () => undefined,
    });
  }

  loadLatest(): void {
    this.list(false, 0, 8).subscribe({ next: (page) => this.latest.set(page.items), error: () => undefined });
  }

  list(unreadOnly: boolean, page: number, size: number): Observable<Page<AppNotification>> {
    return this.http.get<Page<AppNotification>>(`${API}/notifications`, { params: { unreadOnly, page, size } });
  }

  markRead(n: AppNotification): void {
    if (n.readAt) return;
    this.http.post<void>(`${API}/notifications/${n.id}/read`, {}).subscribe({ next: () => this.refreshCount(), error: () => undefined });
    const now = new Date().toISOString();
    this.latest.update((list) => list.map((x) => (x.id === n.id ? { ...x, readAt: now } : x)));
  }

  markAllRead(): Observable<{ updated: number }> {
    return this.http.post<{ updated: number }>(`${API}/notifications/read-all`, {}).pipe(
      tap(() => {
        this.unread.set(0);
        const now = new Date().toISOString();
        this.latest.update((list) => list.map((x) => ({ ...x, readAt: x.readAt ?? now })));
      }),
    );
  }

  /** Marks as read and follows the notification's in-app link. */
  open(n: AppNotification): void {
    this.markRead(n);
    if (n.link) this.router.navigateByUrl(n.link);
  }

  private stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }
}
