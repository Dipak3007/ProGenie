import { addDays, hhmm, humanize, inr, newIdempotencyKey, statusMeta, telLink } from './format';
import { niceMax, shortLabel } from './ui/charts';
import { formatDay } from './ui/day.pipe';

describe('format helpers', () => {
  it('labels and colours every booking status', () => {
    expect(statusMeta('REQUESTED')).toEqual({ label: 'Waiting for Genie', tone: 'warning' });
    expect(statusMeta('COMPLETED').tone).toBe('success');
    expect(statusMeta('SOMETHING_NEW')).toEqual({ label: 'Something new', tone: 'neutral' });
    expect(statusMeta(null).label).toBe('—');
  });

  it('formats rupees the Indian way', () => {
    expect(inr(125000)).toBe('₹1,25,000');
    expect(inr(49.5)).toBe('₹49.50');
    expect(inr(-24.4)).toBe('-₹24.40');
    expect(inr(null)).toBe('₹0');
  });

  it('shifts calendar dates across month ends', () => {
    expect(addDays('2026-09-30', 1)).toBe('2026-10-01');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
  });

  it('builds tel: links for Indian numbers', () => {
    expect(telLink('9825000005')).toBe('tel:+919825000005');
    expect(telLink('+91 98250 00005')).toBe('tel:+919825000005');
    expect(telLink(null)).toBeNull();
  });

  it('creates a fresh idempotency key each time', () => {
    const a = newIdempotencyKey();
    expect(a.length).toBeGreaterThan(16);
    expect(newIdempotencyKey()).not.toBe(a);
  });

  it('small text helpers', () => {
    expect(humanize('IN_PROGRESS')).toBe('In progress');
    expect(hhmm('09:30:00')).toBe('09:30');
  });
});

describe('chart helpers', () => {
  it('rounds the axis up to a clean number', () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(7)).toBe(10);
    expect(niceMax(1234)).toBe(2000);
    expect(niceMax(2300)).toBe(2500);
  });

  it('shortens ISO dates for axis labels', () => {
    expect(shortLabel('2026-09-07')).toBe('7 Sep');
    expect(shortLabel('Electrician')).toBe('Electrician');
  });
});

describe('calendar days', () => {
  it('prints API dates as the same calendar day in any time zone', () => {
    expect(formatDay('2026-09-26', 'EEE d MMM')).toBe('Sat 26 Sep');
    expect(formatDay('2026-09-27', 'EEE')).toBe('Sun');
    expect(formatDay('2026-12-31', 'd MMM y')).toBe('31 Dec 2026');
  });
});
