export function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('en', {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
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
