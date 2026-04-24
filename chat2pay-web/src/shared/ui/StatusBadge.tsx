import { cn } from '@/shared/lib/cn';

type StatusTone = 'neutral' | 'positive' | 'danger' | 'attention';

function toneForStatus(status: string): StatusTone {
  if (status === 'COMPLETED' || status === 'CONFIRMED' || status === 'PASSED') {
    return 'positive';
  }

  if (status === 'FAILED' || status === 'CANCELLED') {
    return 'danger';
  }

  if (status === 'AWAITING_CONFIRMATION' || status === 'EXECUTING') {
    return 'attention';
  }

  return 'neutral';
}

export function StatusBadge({ value }: { value: string }) {
  const tone = toneForStatus(value);

  return (
    <span
      className={cn(
        'brand-status-badge',
        value === 'ACTIVE' && 'border-brand-black bg-brand-black text-white',
        tone === 'positive' && 'border-emerald-700 bg-emerald-50 text-emerald-800',
        tone === 'danger' && 'border-red-700 bg-red-50 text-red-800',
        tone === 'attention' && 'border-amber-600 bg-amber-50 text-amber-800',
        tone === 'neutral' && value !== 'ACTIVE' && 'border-brand-line bg-white text-brand-charcoal',
      )}
    >
      <span
        className={cn(
          'brand-status-indicator',
          value === 'ACTIVE' && 'bg-brand-red animate-pulse text-brand-red',
          tone === 'positive' && 'bg-emerald-700 text-emerald-700',
          tone === 'danger' && 'bg-red-700 text-red-700',
          tone === 'attention' && 'bg-amber-600 text-amber-600',
          tone === 'neutral' && value !== 'ACTIVE' && 'bg-brand-black text-brand-black',
        )}
      />
      {value.replaceAll('_', ' ')}
    </span>
  );
}
