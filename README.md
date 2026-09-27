# ProGenie 2.0

**ProGenie = Professional Genie.** A home-services marketplace connecting customers in Ahmedabad with verified local professionals ("Genies"): electricians, plumbers, cleaners, AC technicians and more.

This is the rewrite of a college JSP/servlet project into two independently deployable services:

| Service | Stack | Folder |
| --- | --- | --- |
| **progenie-api** | Spring Boot 4.1 · Java 25 · PostgreSQL 17 + PostGIS · Flyway · JWT | [`api/`](api) |
| **progenie-web** | Angular 21 (standalone, zoneless, signals) · Tailwind CSS v4 · Vitest | [`web/`](web) |

Database DDL and Day-0 data live in the sibling folder **`../ProGenieV2-Database`** and are applied by Flyway on API startup.

## Quick start

```bash
cd infra && cp .env.example .env && docker compose up -d   # PostgreSQL + PostGIS, Redis, Mailpit
cd ../api && mvn spring-boot:run                             # http://localhost:8080
cd ../web && npm ci && npm start                             # http://localhost:4200
```

Log in with `customer@example.com` / `ProGenie@123`.

Full instructions, prerequisites, troubleshooting and the folder structure: **[docs/LOCAL_SETUP.md](docs/LOCAL_SETUP.md)**.
Deploying a SIT server (Docker Compose + Caddy on one Linux server): **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**, using `infra/compose.sit.yaml` and `infra/scripts/`.
Backend rules and every endpoint: **[docs/API.md](docs/API.md)** (live docs at http://localhost:8080/swagger-ui.html).

## Highlights

- Stateless JWT auth: 15-minute access token in memory, rotating refresh token in an HttpOnly cookie with reuse detection.
- Modular monolith: identity, customer, catalog, provider, pricing, booking, payment, review, notification, analytics, admin, support.
- Full booking lifecycle: slots, Genie accept/decline with auto-expiry, 4-digit start code, completion with extras, reschedule, 2-hour free cancellation with a fee after that.
- Cash or online payment (gateway port with HMAC-verified callbacks and webhooks), double-entry ledger, Genie wallet and weekly payouts.
- Genie onboarding with KYC upload (magic-byte file checks, masked ID numbers) and an admin verification queue; reliability flags for repeated cancellations.
- Transparent pricing: Genie's price + distance-based travel fee + optional tip; 5% platform commission on the service price only.
- Admin verification required before any Genie can be booked.
- No double booking, guaranteed by a PostgreSQL exclusion constraint.
- Complete web app for all three roles: booking checkout with live slots and prices, customer bookings (start code, online payment, reviews, reschedule/cancel), Genie console (get-verified wizard with KYC upload, jobs, availability, earnings), and an admin back office (verification, bookings, users, catalog and pricing, payouts, analytics).
- Launch readiness: one-time-code login and password reset, SMS/WhatsApp/email with per-user preferences, Razorpay Checkout, refunds with PDF receipts and credit notes, complaint tickets with deadlines and an admin resolution flow, versioned legal pages with re-acceptance, and DPDP rights (data export, account deletion).
- Mobile-first responsive UI with the final ProGenie brand, tested in a real browser from 360 px phones to desktop.
