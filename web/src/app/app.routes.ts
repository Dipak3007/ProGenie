import { Routes } from '@angular/router';

import { guestOnly, requireRole } from './core/auth/guards';

/**
 * Every feature is lazy-loaded, so the first page only downloads what it needs.
 * Browsing is public; account areas are protected by role guards.
 * Paths match the in-app links the API puts in notifications (e.g. /account/bookings/:id, /genie/onboarding).
 */
export const routes: Routes = [
  { path: '', title: 'ProGenie · Your wish, handled', loadComponent: () => import('./features/home/home.page') },
  { path: 'services', title: 'All services · ProGenie', loadComponent: () => import('./features/catalog/services.page') },
  { path: 'services/:slug', loadComponent: () => import('./features/catalog/category.page') },
  { path: 'genies/:id', loadComponent: () => import('./features/genies/genie-profile.page') },
  { path: 'login', title: 'Log in · ProGenie', canActivate: [guestOnly], loadComponent: () => import('./features/auth/login.page') },
  { path: 'register', title: 'Sign up · ProGenie', canActivate: [guestOnly], loadComponent: () => import('./features/auth/register.page') },
  {
    path: 'forgot-password',
    title: 'Reset password · ProGenie',
    canActivate: [guestOnly],
    loadComponent: () => import('./features/auth/forgot-password.page'),
  },
  { path: 'legal/:kind', loadComponent: () => import('./features/legal/legal.page') },

  // ---------------------------------------------------------------- customer
  {
    path: 'book/:genieId',
    title: 'Book a Genie · ProGenie',
    canActivate: [requireRole('CUSTOMER')],
    loadComponent: () => import('./features/customer/checkout.page'),
  },
  {
    path: 'account',
    canActivate: [requireRole('CUSTOMER')],
    loadComponent: () => import('./features/account/account.page'),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'bookings' },
      { path: 'bookings', title: 'My bookings · ProGenie', loadComponent: () => import('./features/customer/bookings.page') },
      { path: 'bookings/:id', title: 'Booking · ProGenie', loadComponent: () => import('./features/customer/booking-detail.page') },
      { path: 'addresses', title: 'Saved addresses · ProGenie', loadComponent: () => import('./features/customer/addresses.page') },
      { path: 'favourites', title: 'Favourite Genies · ProGenie', loadComponent: () => import('./features/customer/favourites.page') },
      { path: 'profile', title: 'Profile · ProGenie', loadComponent: () => import('./features/customer/profile.page') },
      { path: 'tickets', title: 'Reported problems · ProGenie', loadComponent: () => import('./features/support/tickets.page') },
      { path: 'tickets/:id', title: 'Report · ProGenie', loadComponent: () => import('./features/support/ticket.page') },
      { path: 'privacy', title: 'Settings & privacy · ProGenie', loadComponent: () => import('./features/account/settings.page') },
    ],
  },
  {
    path: 'notifications',
    title: 'Notifications · ProGenie',
    canActivate: [requireRole()],
    loadComponent: () => import('./features/notifications/notifications.page'),
  },

  // ---------------------------------------------------------------- Genie
  {
    path: 'genie',
    canActivate: [requireRole('GENIE')],
    loadComponent: () => import('./features/genie-console/genie-console.page'),
    children: [
      { path: '', pathMatch: 'full', title: 'Genie dashboard · ProGenie', loadComponent: () => import('./features/genie-console/dashboard.page') },
      { path: 'onboarding', title: 'Profile & services · ProGenie', loadComponent: () => import('./features/genie-console/onboarding.page') },
      { path: 'jobs', title: 'Jobs · ProGenie', loadComponent: () => import('./features/genie-console/jobs.page') },
      { path: 'bookings/:id', title: 'Job · ProGenie', loadComponent: () => import('./features/genie-console/job-detail.page') },
      { path: 'availability', title: 'Availability · ProGenie', loadComponent: () => import('./features/genie-console/availability.page') },
      { path: 'wallet', title: 'Earnings · ProGenie', loadComponent: () => import('./features/genie-console/wallet.page') },
      { path: 'reviews', title: 'Reviews · ProGenie', loadComponent: () => import('./features/genie-console/reviews.page') },
      { path: 'account', title: 'Account · ProGenie', loadComponent: () => import('./features/customer/profile.page') },
      { path: 'tickets', title: 'Reported problems · ProGenie', loadComponent: () => import('./features/support/tickets.page') },
      { path: 'tickets/:id', title: 'Report · ProGenie', loadComponent: () => import('./features/support/ticket.page') },
      { path: 'privacy', title: 'Settings & privacy · ProGenie', loadComponent: () => import('./features/account/settings.page') },
    ],
  },

  // ---------------------------------------------------------------- admin
  {
    path: 'admin',
    canActivate: [requireRole('ADMIN')],
    loadComponent: () => import('./features/admin-console/admin-console.page'),
    children: [
      { path: '', pathMatch: 'full', title: 'Admin overview · ProGenie', loadComponent: () => import('./features/admin-console/overview.page') },
      { path: 'genies', title: 'Genies · Admin', loadComponent: () => import('./features/admin-console/genies.page') },
      { path: 'genies/:id', title: 'Genie review · Admin', loadComponent: () => import('./features/admin-console/genie-review.page') },
      { path: 'bookings', title: 'Bookings · Admin', loadComponent: () => import('./features/admin-console/bookings.page') },
      { path: 'bookings/:id', title: 'Booking · Admin', loadComponent: () => import('./features/admin-console/booking.page') },
      { path: 'users', title: 'Users · Admin', loadComponent: () => import('./features/admin-console/users.page') },
      { path: 'catalog', title: 'Catalog · Admin', loadComponent: () => import('./features/admin-console/catalog.page') },
      { path: 'finance', title: 'Finance · Admin', loadComponent: () => import('./features/admin-console/finance.page') },
      { path: 'inbox', title: 'Inbox · Admin', loadComponent: () => import('./features/admin-console/inbox.page') },
      { path: 'tickets', title: 'Complaints · Admin', loadComponent: () => import('./features/admin-console/tickets.page') },
      { path: 'tickets/:id', title: 'Complaint · Admin', loadComponent: () => import('./features/admin-console/ticket.page') },
      { path: 'messages', title: 'Messages · Admin', loadComponent: () => import('./features/admin-console/messages.page') },
      { path: 'legal', title: 'Policies · Admin', loadComponent: () => import('./features/admin-console/legal.page') },
      { path: 'legal/:kind', title: 'Edit policy · Admin', loadComponent: () => import('./features/admin-console/legal-editor.page') },
      { path: 'deletions', title: 'Account deletions · Admin', loadComponent: () => import('./features/admin-console/deletions.page') },
      { path: 'account', title: 'Account · Admin', loadComponent: () => import('./features/customer/profile.page') },
      { path: 'settings', title: 'Settings · Admin', loadComponent: () => import('./features/account/settings.page') },
    ],
  },

  { path: '**', title: 'Page not found · ProGenie', loadComponent: () => import('./features/not-found.page') },
];
