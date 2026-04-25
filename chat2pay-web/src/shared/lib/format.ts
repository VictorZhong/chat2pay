export function formatDateTime(value: string) {
  // Show the user's local time but always include the zone abbreviation so
  // there is no ambiguity (e.g. "Apr 26, 14:32 HKT").
  return new Intl.DateTimeFormat('en', {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZoneName: 'short',
  }).format(new Date(value));
}

/**
 * Full ISO-style absolute timestamp for tooltips. Includes the timezone offset
 * so the value can be unambiguously read regardless of locale.
 */
export function formatAbsoluteTimestamp(value: string) {
  const date = new Date(value);
  return new Intl.DateTimeFormat('en', {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
    timeZoneName: 'short',
  }).format(date);
}

export function formatIsoTimestamp(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toISOString();
}

export function formatRelativeTime(value: string) {
  const date = new Date(value);
  const timestamp = date.getTime();
  if (Number.isNaN(timestamp)) return value;

  const diffSeconds = Math.round((timestamp - Date.now()) / 1000);
  const absSeconds = Math.abs(diffSeconds);
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 60 * 60 * 24 * 365],
    ['month', 60 * 60 * 24 * 30],
    ['day', 60 * 60 * 24],
    ['hour', 60 * 60],
    ['minute', 60],
  ];
  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

  for (const [unit, secondsPerUnit] of units) {
    if (absSeconds >= secondsPerUnit) {
      return formatter.format(Math.round(diffSeconds / secondsPerUnit), unit);
    }
  }

  return formatter.format(diffSeconds, 'second');
}

export function formatWorkflowState(state: string) {
  return state
    .toLowerCase()
    .split('_')
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1))
    .join(' ');
}

export function formatCurrency(value?: number | null, currency = 'HKD') {
  if (value === undefined || value === null) {
    return 'Pending';
  }

  return new Intl.NumberFormat('en-HK', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
  }).format(value);
}

export function initialsOf(name: string) {
  return name
    .split(' ')
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');
}
