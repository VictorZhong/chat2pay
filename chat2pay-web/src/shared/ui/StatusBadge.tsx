import { cn } from '@/shared/lib/cn';

type StatusTone = 'neutral' | 'positive' | 'danger' | 'attention';

function toneForStatus(status: string): StatusTone {
  if (status === 'COMPLETED' || status === 'CONFIRMED' || status === 'PASSED') {
    return 'positive';
  }

  if (status === 'FAILED' || status === 'CANCELLED') {
    return 'danger';
  }

  if (status === 'AWAITING_USER_CONFIRMATION' || status === 'PROPOSED') {
    return 'attention';
  }

  return 'neutral';
}

export function StatusBadge({ value }: { value: string }) {
  const tone = toneForStatus(value);

  return (
    <span
      className={cn(
        'brand-chip',
        tone === 'positive' && 'border-emerald-600 text-emerald-700',
        tone === 'danger' && 'border-red-700 text-red-700',
        tone === 'attention' && 'border-amber-600 text-amber-700',
      )}
    >
      {value.replaceAll('_', ' ')}
    </span>
  );
}
