import type { ChatSessionDetail, ChatSessionSummary, ConversationState, ChatSessionStatus } from '@/shared/api/contracts';
import { cn } from '@/shared/lib/cn';

type ConversationView = {
  status: ChatSessionStatus;
  state: ConversationState;
};

type Pill = {
  label: string;
  tone: 'neutral' | 'attention' | 'positive' | 'danger' | 'info';
};

function derive(view: ConversationView): Pill | null {
  // Terminal session statuses always win.
  if (view.status === 'COMPLETED') return { label: 'Completed', tone: 'positive' };
  if (view.status === 'FAILED') return { label: 'Failed', tone: 'danger' };
  if (view.status === 'CANCELLED') return { label: 'Cancelled', tone: 'danger' };
  if (view.status === 'ARCHIVED') return { label: 'Archived', tone: 'neutral' };

  // ACTIVE: surface the conversation state, since "ACTIVE" alone doesn't tell
  // the user anything actionable. IDLE means "ready for a new request".
  switch (view.state) {
    case 'COLLECTING_DETAILS':
      return { label: 'Collecting details', tone: 'attention' };
    case 'AWAITING_PAYEE_SELECTION':
      return { label: 'Awaiting payee selection', tone: 'attention' };
    case 'AWAITING_DEBIT_ACCOUNT_SELECTION':
      return { label: 'Awaiting debit account selection', tone: 'attention' };
    case 'AWAITING_CONFIRMATION':
      return { label: 'Awaiting confirmation', tone: 'attention' };
    case 'EXECUTING':
      return { label: 'Processing payment', tone: 'info' };
    case 'COMPLETED':
      return { label: 'Completed', tone: 'positive' };
    case 'FAILED':
      return { label: 'Failed', tone: 'danger' };
    case 'CANCELLED':
      return { label: 'Cancelled', tone: 'danger' };
    case 'IDLE':
    default:
      return null;
  }
}

export function ConversationStatusPill({
  session,
}: {
  session: ChatSessionDetail | ChatSessionSummary;
}) {
  const pill = derive({ status: session.status, state: session.state });
  if (!pill) return null;

  return (
    <span
      className={cn(
        'brand-status-badge',
        pill.tone === 'neutral' && 'border-brand-line bg-white text-brand-charcoal',
        pill.tone === 'attention' && 'border-amber-600 bg-amber-50 text-amber-800',
        pill.tone === 'positive' && 'border-emerald-700 bg-emerald-50 text-emerald-800',
        pill.tone === 'danger' && 'border-red-700 bg-red-50 text-red-800',
        pill.tone === 'info' && 'border-brand-black bg-brand-black text-white',
      )}
      title={pill.label}
    >
      <span
        className={cn(
          'brand-status-indicator',
          pill.tone === 'neutral' && 'bg-brand-black text-brand-black',
          pill.tone === 'attention' && 'bg-amber-600 text-amber-600',
          pill.tone === 'positive' && 'bg-emerald-700 text-emerald-700',
          pill.tone === 'danger' && 'bg-red-700 text-red-700',
          pill.tone === 'info' && 'bg-brand-red animate-pulse text-brand-red',
        )}
      />
      {pill.label}
    </span>
  );
}
