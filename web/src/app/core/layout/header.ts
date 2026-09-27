import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter } from 'rxjs';
import {
  LucideBell as Bell,
  LucideChevronDown as ChevronDown,
  LucideDynamicIcon,
  LucideLogOut as LogOut,
  LucideMapPin as MapPin,
  LucideMenu as Menu,
  LucideUser as User,
  LucideX as X,
} from '@lucide/angular';

import { Role } from '../api/models';
import { AuthStore } from '../auth/auth-store';
import { NotificationStore } from '../notifications/notification-store';
import { Logo } from '../../shared/ui/logo';
import { IST } from '../../shared/format';

interface Link {
  path: string;
  label: string;
  exact?: boolean;
}

const LINKS: Record<Role | 'GUEST', Link[]> = {
  GUEST: [
    { path: '/', label: 'Home', exact: true },
    { path: '/services', label: 'Services' },
    { path: '/register', label: 'Become a Genie' },
  ],
  CUSTOMER: [
    { path: '/', label: 'Home', exact: true },
    { path: '/services', label: 'Services' },
    { path: '/account/bookings', label: 'My bookings' },
  ],
  GENIE: [
    { path: '/genie', label: 'Dashboard', exact: true },
    { path: '/genie/jobs', label: 'Jobs' },
    { path: '/genie/wallet', label: 'Earnings' },
  ],
  ADMIN: [
    { path: '/admin', label: 'Overview', exact: true },
    { path: '/admin/genies', label: 'Genies' },
    { path: '/admin/bookings', label: 'Bookings' },
    { path: '/admin/tickets', label: 'Complaints' },
  ],
};

const MENU: Record<Role, Link[]> = {
  CUSTOMER: [
    { path: '/account/bookings', label: 'My bookings' },
    { path: '/account/addresses', label: 'Saved addresses' },
    { path: '/account/favourites', label: 'Favourite Genies' },
    { path: '/account/tickets', label: 'Reported problems' },
    { path: '/account/profile', label: 'Profile & password' },
    { path: '/account/privacy', label: 'Settings & privacy' },
  ],
  GENIE: [
    { path: '/genie', label: 'Dashboard' },
    { path: '/genie/onboarding', label: 'Profile & services' },
    { path: '/genie/availability', label: 'Availability' },
    { path: '/genie/reviews', label: 'Reviews' },
    { path: '/genie/tickets', label: 'Reported problems' },
    { path: '/genie/account', label: 'Account & password' },
    { path: '/genie/privacy', label: 'Settings & privacy' },
  ],
  ADMIN: [
    { path: '/admin', label: 'Overview' },
    { path: '/admin/users', label: 'Users' },
    { path: '/admin/catalog', label: 'Catalog' },
    { path: '/admin/finance', label: 'Finance' },
    { path: '/admin/tickets', label: 'Complaints' },
    { path: '/admin/messages', label: 'Messages' },
    { path: '/admin/legal', label: 'Policies' },
    { path: '/admin/account', label: 'Account & password' },
  ],
};

/** Sticky, frosted header. Full nav on md+ screens; a slide-down menu on phones. Links adapt to the user's role. */
@Component({
  selector: 'pg-header',
  imports: [RouterLink, RouterLinkActive, DatePipe, LucideDynamicIcon, Logo],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:click)': 'onDocumentClick($event)', '(document:keydown.escape)': 'closePopovers()' },
  template: `
    <header class="sticky top-0 z-40 border-b border-white/10 bg-navy/90 text-white backdrop-blur-md">
      <div class="container-page flex h-[calc(var(--header-h)-1px)] items-center justify-between gap-3">
        <a routerLink="/" aria-label="ProGenie home" class="shrink-0"><pg-logo tone="dark" [size]="36" /></a>

        <span class="hidden items-center gap-1 rounded-full bg-white/10 px-3 py-1 text-xs font-medium text-brand-mist xl:inline-flex">
          <svg [lucideIcon]="MapPin" [size]="14" aria-hidden="true"></svg> Ahmedabad
        </span>

        <nav class="ml-auto hidden items-center gap-1 md:flex" aria-label="Main">
          @for (link of links(); track link.path) {
            <a
              [routerLink]="link.path"
              routerLinkActive="bg-white/10 text-white"
              [routerLinkActiveOptions]="{ exact: !!link.exact }"
              class="rounded-full px-3 py-2 text-sm font-medium text-brand-mist hover:text-white lg:px-4"
              >{{ link.label }}</a
            >
          }
        </nav>

        <div class="flex items-center gap-1 md:gap-2">
          @if (auth.user(); as user) {
            <!-- Notifications -->
            <div class="relative" data-popover>
              <button
                type="button"
                class="relative grid size-11 place-items-center rounded-full hover:bg-white/10"
                [attr.aria-label]="notes.unread() ? notes.unread() + ' unread notifications' : 'Notifications'"
                [attr.aria-expanded]="bellOpen()"
                (click)="toggleBell()"
              >
                <svg [lucideIcon]="Bell" [size]="20" aria-hidden="true"></svg>
                @if (notes.unread()) {
                  <span class="absolute right-1.5 top-1.5 grid min-w-5 place-items-center rounded-full bg-gold px-1 text-[11px] font-bold leading-5 text-ink">
                    {{ notes.unread() > 9 ? '9+' : notes.unread() }}
                  </span>
                }
              </button>
              @if (bellOpen()) {
                <div class="fixed inset-x-3 top-[calc(var(--header-h)+0.5rem)] z-50 overflow-hidden rounded-2xl bg-white text-ink shadow-2xl ring-1 ring-line sm:absolute sm:inset-x-auto sm:right-0 sm:top-12 sm:w-96">
                  <div class="flex items-center justify-between border-b border-line px-4 py-3">
                    <p class="font-semibold">Notifications</p>
                    <button type="button" class="text-sm font-semibold text-brand hover:underline disabled:opacity-50" [disabled]="!notes.unread()" (click)="markAll()">
                      Mark all read
                    </button>
                  </div>
                  <ul class="max-h-[60dvh] divide-y divide-line overflow-y-auto">
                    @for (n of notes.latest(); track n.id) {
                      <li>
                        <button type="button" class="flex w-full gap-3 px-4 py-3 text-left hover:bg-surface" (click)="openNote(n)">
                          <span class="mt-1.5 size-2 shrink-0 rounded-full" [class]="n.readAt ? 'bg-transparent' : 'bg-brand'" aria-hidden="true"></span>
                          <span class="min-w-0">
                            <span class="block text-sm font-semibold" [class.text-muted]="n.readAt">{{ n.title }}</span>
                            @if (n.body) {
                              <span class="mt-0.5 line-clamp-2 block text-sm text-muted">{{ n.body }}</span>
                            }
                            <span class="mt-1 block text-xs text-muted">{{ n.createdAt | date: 'd MMM, h:mm a' : ist }}</span>
                          </span>
                        </button>
                      </li>
                    } @empty {
                      <li class="px-4 py-8 text-center text-sm text-muted">You're all caught up.</li>
                    }
                  </ul>
                  <a routerLink="/notifications" class="block border-t border-line px-4 py-3 text-center text-sm font-semibold text-brand hover:bg-surface">
                    See all notifications
                  </a>
                </div>
              }
            </div>

            <!-- Account menu (md+) -->
            <div class="relative hidden md:block" data-popover>
              <button type="button" class="btn text-white hover:bg-white/10" [attr.aria-expanded]="menuOpen()" (click)="toggleMenu()">
                <svg [lucideIcon]="UserIcon" [size]="18" aria-hidden="true"></svg> {{ auth.firstName() }}
                <svg [lucideIcon]="ChevronDown" [size]="16" aria-hidden="true"></svg>
              </button>
              @if (menuOpen()) {
                <div class="absolute right-0 top-12 z-50 w-64 overflow-hidden rounded-2xl bg-white py-2 text-ink shadow-2xl ring-1 ring-line">
                  <div class="px-4 pb-2 pt-1">
                    <p class="truncate font-semibold">{{ user.fullName }}</p>
                    <p class="text-xs text-muted">{{ roleLabel() }}</p>
                  </div>
                  <div class="border-t border-line pt-1">
                    @for (item of menu(); track item.path) {
                      <a [routerLink]="item.path" class="block px-4 py-2.5 text-sm hover:bg-surface">{{ item.label }}</a>
                    }
                    <a routerLink="/notifications" class="block px-4 py-2.5 text-sm hover:bg-surface">Notifications</a>
                  </div>
                  <div class="mt-1 border-t border-line pt-1">
                    <button type="button" class="flex w-full items-center gap-2 px-4 py-2.5 text-left text-sm text-rose-700 hover:bg-rose-50" (click)="auth.logout()">
                      <svg [lucideIcon]="LogOut" [size]="16" aria-hidden="true"></svg> Log out
                    </button>
                  </div>
                </div>
              }
            </div>
          } @else {
            <a routerLink="/login" class="btn hidden text-white hover:bg-white/10 md:inline-flex">Log in</a>
            <a routerLink="/register" class="btn-gold hidden md:inline-flex">Sign up</a>
          }

          <button
            type="button"
            class="grid size-11 place-items-center rounded-full hover:bg-white/10 md:hidden"
            [attr.aria-expanded]="open()"
            aria-controls="mobile-menu"
            [attr.aria-label]="open() ? 'Close menu' : 'Open menu'"
            (click)="open.set(!open())"
          >
            <svg [lucideIcon]="open() ? X : Menu" [size]="24" aria-hidden="true"></svg>
          </button>
        </div>
      </div>

      @if (open()) {
        <nav id="mobile-menu" class="max-h-[calc(100dvh-var(--header-h))] overflow-y-auto border-t border-white/10 bg-navy md:hidden" aria-label="Mobile">
          <div class="container-page flex flex-col gap-1 py-4">
            @for (link of links(); track link.path) {
              <a [routerLink]="link.path" class="rounded-xl px-4 py-3 text-base font-medium text-brand-mist hover:bg-white/10">{{ link.label }}</a>
            }
            @if (auth.user(); as user) {
              <p class="mt-3 px-4 text-xs font-semibold uppercase tracking-widest text-brand-soft">{{ user.fullName }} · {{ roleLabel() }}</p>
              @for (item of menu(); track item.path) {
                <a [routerLink]="item.path" class="rounded-xl px-4 py-3 text-base font-medium text-brand-mist hover:bg-white/10">{{ item.label }}</a>
              }
              <button type="button" class="btn mt-3 border border-white/20 text-white" (click)="auth.logout()">Log out</button>
            } @else {
              <div class="mt-3 grid grid-cols-2 gap-2">
                <a routerLink="/login" class="btn border border-white/20 text-white">Log in</a>
                <a routerLink="/register" class="btn-gold">Sign up</a>
              </div>
            }
          </div>
        </nav>
      }
    </header>
  `,
})
export class Header {
  protected readonly auth = inject(AuthStore);
  protected readonly notes = inject(NotificationStore);
  private readonly el = inject(ElementRef<HTMLElement>);

  protected readonly open = signal(false);
  protected readonly bellOpen = signal(false);
  protected readonly menuOpen = signal(false);
  protected readonly ist = IST;

  protected readonly links = computed(() => LINKS[this.auth.role() ?? 'GUEST']);
  protected readonly menu = computed(() => (this.auth.role() ? MENU[this.auth.role()!] : []));
  protected readonly roleLabel = computed(() => ({ CUSTOMER: 'Customer', GENIE: 'Genie', ADMIN: 'Administrator' })[this.auth.role() ?? 'CUSTOMER']);

  protected readonly Menu = Menu;
  protected readonly X = X;
  protected readonly MapPin = MapPin;
  protected readonly UserIcon = User;
  protected readonly LogOut = LogOut;
  protected readonly Bell = Bell;
  protected readonly ChevronDown = ChevronDown;

  constructor() {
    // Close menus after every navigation.
    inject(Router)
      .events.pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        this.open.set(false);
        this.closePopovers();
      });
  }

  protected toggleBell(): void {
    const next = !this.bellOpen();
    this.closePopovers();
    this.bellOpen.set(next);
    if (next) {
      this.open.set(false);
      this.notes.loadLatest();
    }
  }

  protected toggleMenu(): void {
    const next = !this.menuOpen();
    this.closePopovers();
    this.menuOpen.set(next);
  }

  protected closePopovers(): void {
    this.bellOpen.set(false);
    this.menuOpen.set(false);
  }

  protected openNote(n: Parameters<NotificationStore['open']>[0]): void {
    this.closePopovers();
    this.notes.open(n);
  }

  protected markAll(): void {
    this.notes.markAllRead().subscribe({ error: () => undefined });
  }

  /** Clicking outside any popover closes it. */
  protected onDocumentClick(event: MouseEvent): void {
    const target = event.target as HTMLElement | null;
    if (!target || !this.el.nativeElement.contains(target) || !target.closest('[data-popover]')) {
      this.closePopovers();
    }
  }
}
