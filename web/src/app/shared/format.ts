/**
 * Small presentation helpers shared by every console.
 * All times are shown in India time (the trial city is Ahmedabad), whatever the browser's zone.
 */

export const IST = '+0530';
export const IST_ZONE = 'Asia/Kolkata';

export type Tone = 'neutral' | 'info' | 'brand' | 'success' | 'warning' | 'danger';

/** Tailwind classes per tone (text + soft background + ring), used by badges and banners. */
export const TONE_CLASSES: Record<Tone, string> = {
  neutral: 'bg-slate-100 text-slate-700 ring-slate-200',
  info: 'bg-sky-50 text-sky-800 ring-sky-200',
  brand: 'bg-brand-mist text-brand-600 ring-brand-soft/60',
  success: 'bg-emerald-50 text-emerald-800 ring-emerald-200',
  warning: 'bg-amber-50 text-amber-900 ring-amber-200',
  danger: 'bg-rose-50 text-rose-800 ring-rose-200',
};

const STATUS: Record<string, { label: string; tone: Tone }> = {
  // bookings
  REQUESTED: { label: 'Waiting for Genie', tone: 'warning' },
  ACCEPTED: { label: 'Confirmed', tone: 'brand' },
  IN_PROGRESS: { label: 'In progress', tone: 'info' },
  COMPLETED: { label: 'Completed', tone: 'success' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
  REJECTED: { label: 'Declined', tone: 'danger' },
  EXPIRED: { label: 'Expired', tone: 'neutral' },
  // payments
  UNPAID: { label: 'Unpaid', tone: 'warning' },
  PAID: { label: 'Paid', tone: 'success' },
  REFUNDED: { label: 'Refunded', tone: 'info' },
  WAIVED: { label: 'Waived', tone: 'neutral' },
  DUE: { label: 'Fee due', tone: 'danger' },
  PENDING: { label: 'Pending', tone: 'warning' },
  SUCCEEDED: { label: 'Succeeded', tone: 'success' },
  FAILED: { label: 'Failed', tone: 'danger' },
  // Genie verification
  REGISTERED: { label: 'Onboarding', tone: 'neutral' },
  UNDER_REVIEW: { label: 'Under review', tone: 'warning' },
  NEEDS_CHANGES: { label: 'Needs changes', tone: 'warning' },
  APPROVED: { label: 'Approved', tone: 'success' },
  SUSPENDED: { label: 'Suspended', tone: 'danger' },
  // users / inbox
  ACTIVE: { label: 'Active', tone: 'success' },
  DELETED: { label: 'Deleted', tone: 'neutral' },
  NEW: { label: 'New', tone: 'brand' },
  RESOLVED: { label: 'Resolved', tone: 'success' },
  // refunds and receipts
  PARTIALLY_REFUNDED: { label: 'Partly refunded', tone: 'info' },
  PROCESSED: { label: 'Refunded', tone: 'success' },
  RECEIPT: { label: 'Receipt', tone: 'brand' },
  CREDIT_NOTE: { label: 'Credit note', tone: 'info' },
  // complaint tickets
  OPEN: { label: 'Open', tone: 'warning' },
  IN_REVIEW: { label: 'In review', tone: 'brand' },
  AWAITING_REPLY: { label: 'Waiting for you', tone: 'warning' },
  CLOSED: { label: 'Closed', tone: 'neutral' },
  URGENT: { label: 'Urgent', tone: 'danger' },
  HIGH: { label: 'High', tone: 'warning' },
  NORMAL: { label: 'Normal', tone: 'neutral' },
  // outgoing messages, exports, deletions
  SENT: { label: 'Sent', tone: 'success' },
  SKIPPED: { label: 'Skipped', tone: 'neutral' },
  READY: { label: 'Ready', tone: 'success' },
  SCHEDULED: { label: 'Scheduled', tone: 'warning' },
  DONE: { label: 'Done', tone: 'neutral' },
  BLOCKED: { label: 'Blocked', tone: 'danger' },
};

/** What went wrong, in the customer's words. */
export const TICKET_CATEGORY_LABEL: Record<string, string> = {
  SAFETY: 'Safety or harassment',
  DAMAGE: 'Damage to my property',
  NO_SHOW: 'No-show or very late',
  QUALITY: 'Poor or incomplete work',
  PAYMENT: 'Overcharged or payment problem',
  BEHAVIOUR: 'Rude or unprofessional behaviour',
  PRIVACY: 'Privacy request',
  OTHER: 'Something else',
};

/** Saves a downloaded file (receipt PDF, data export) under the given name. */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export function statusMeta(status: string | null | undefined): { label: string; tone: Tone } {
  if (!status) return { label: '—', tone: 'neutral' };
  return STATUS[status] ?? { label: humanize(status), tone: 'neutral' };
}

/** "IN_PROGRESS" → "In progress" */
export function humanize(value: string | null | undefined): string {
  if (!value) return '';
  const text = value.replace(/_/g, ' ').toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

const INR_WHOLE = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
const INR_PAISE = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** ₹1,25,000 for whole rupees, ₹49.50 when there are paise (never "₹49.5"). */
export function inr(amount: number | null | undefined): string {
  const value = Number(amount ?? 0);
  return Number.isInteger(Math.round(value * 100) / 100) ? INR_WHOLE.format(value) : INR_PAISE.format(value);
}

/** Today's date in India as "yyyy-MM-dd". */
export function todayIst(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: IST_ZONE }).format(new Date());
}

/** Shifts a "yyyy-MM-dd" date by n days. */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** A random key for the Idempotency-Key header (falls back when crypto.randomUUID is unavailable, e.g. plain http on a LAN IP). */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

export const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/** "09:00:00" → "09:00" */
export function hhmm(time: string): string {
  return time.slice(0, 5);
}

/** Converts a <input type="datetime-local"> value (browser time) to an ISO instant. */
export function localInputToIso(value: string): string {
  return new Date(value).toISOString();
}

/** "tel:" link that works for Indian mobile numbers stored with or without +91. */
export function telLink(phone: string | null | undefined): string | null {
  if (!phone) return null;
  const digits = phone.replace(/[^0-9+]/g, '');
  return `tel:${digits.startsWith('+') || digits.length > 10 ? digits : '+91' + digits}`;
}

/** Google Maps search link for an address (no API key needed). */
export function mapsLink(parts: (string | null | undefined)[]): string {
  const q = parts.filter(Boolean).join(', ');
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(q)}`;
}
