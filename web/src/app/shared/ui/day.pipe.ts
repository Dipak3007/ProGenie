import { Pipe, PipeTransform } from '@angular/core';

const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

export type DayFormat = 'EEE' | 'd' | 'd MMM' | 'EEE d MMM' | 'd MMM y';

/**
 * Formats a calendar date ("2026-09-26") without any time-zone maths.
 * Angular's DatePipe reads date-only strings as local midnight, so formatting them in another zone can show
 * the previous or next day. A calendar day from the API must always print as that same day.
 */
export function formatDay(value: string | null | undefined, format: DayFormat = 'd MMM'): string {
  if (!value) return '';
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  if (!m) return value;
  const [y, mo, d] = [+m[1], +m[2], +m[3]];
  const weekday = DAYS[new Date(Date.UTC(y, mo - 1, d)).getUTCDay()];
  switch (format) {
    case 'EEE':
      return weekday;
    case 'd':
      return String(d);
    case 'EEE d MMM':
      return `${weekday} ${d} ${MONTHS[mo - 1]}`;
    case 'd MMM y':
      return `${d} ${MONTHS[mo - 1]} ${y}`;
    default:
      return `${d} ${MONTHS[mo - 1]}`;
  }
}

@Pipe({ name: 'pgDay' })
export class DayPipe implements PipeTransform {
  transform(value: string | null | undefined, format: DayFormat = 'd MMM'): string {
    return formatDay(value, format);
  }
}
