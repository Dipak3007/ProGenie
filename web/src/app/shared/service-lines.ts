/**
 * Several services in one booking (Phase 3.5C): up to 5 services from the same Genie, each 1–5 times, at most 8 hours
 * together. The API takes them as `items=12:2,15:1` (service id : quantity) in query strings.
 */
export interface ServiceLineSel {
  serviceId: number;
  quantity: number;
}

export const MAX_SERVICES = 5;
export const MAX_QUANTITY = 5;
export const MAX_MINUTES = 8 * 60;

/** `12:2,15:1` */
export function itemsParam(lines: readonly ServiceLineSel[]): string {
  return lines.map((l) => `${l.serviceId}:${l.quantity}`).join(',');
}

/** A booking's catalog services as `12:2,15:1` (custom quote lines and the inspection visit are left out). */
export function bookingItemsParam(b: { items?: readonly { serviceId?: number | null; quantity: number }[] | null; serviceId: number }): string {
  const lines = (b.items ?? []).filter((i): i is { serviceId: number; quantity: number } => i.serviceId != null);
  return lines.length ? itemsParam(lines) : String(b.serviceId);
}

/** Reads `12:2,15:1` (or a single id); drops anything malformed, repeated or over the limits. */
export function parseItems(text: string | null | undefined): ServiceLineSel[] {
  const out: ServiceLineSel[] = [];
  for (const part of (text ?? '').split(',')) {
    const [id, qty] = part.trim().split(':');
    const serviceId = Number(id);
    const quantity = qty === undefined ? 1 : Number(qty);
    if (!Number.isInteger(serviceId) || serviceId <= 0 || !Number.isInteger(quantity)) continue;
    if (quantity < 1 || quantity > MAX_QUANTITY || out.some((l) => l.serviceId === serviceId)) continue;
    out.push({ serviceId, quantity });
    if (out.length === MAX_SERVICES) break;
  }
  return out;
}

/** "45 min", "2 h", "2 h 15 min" */
export function durationText(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h === 0 ? `${m} min` : m === 0 ? `${h} h` : `${h} h ${m} min`;
}
