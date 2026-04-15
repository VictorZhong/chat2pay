import type { ChatSessionSummary } from '@/shared/api/contracts';
import { formatDateTime, formatWorkflowState } from '@/shared/lib/format';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { cn } from '@/shared/lib/cn';

export function ChatHistoryList({
  sessions,
  selectedSessionId,
  onSelect,
  collapsed,
}: {
  sessions: ChatSessionSummary[];
  selectedSessionId?: string;
  onSelect: (sessionId: string) => void;
  collapsed: boolean;
}) {
  return (
    <div className="space-y-2">
      {sessions.map((session) => (
        <button
          key={session.sessionId}
          className={cn(
            'w-full border px-4 py-4 text-left transition',
            selectedSessionId === session.sessionId
              ? 'border-brand-red bg-[#fff4f5]'
              : 'border-brand-line bg-white hover:border-brand-black',
          )}
          onClick={() => onSelect(session.sessionId)}
          title={session.title}
        >
          {collapsed ? (
            <div className="space-y-2">
              <div className="h-2 w-8 bg-brand-red" />
              <p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-gray">
                {session.status.slice(0, 3)}
              </p>
            </div>
          ) : (
            <div className="space-y-3">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-brand-black">{session.title}</p>
                  <p className="mt-1 text-xs uppercase tracking-[0.12em] text-brand-gray">
                    {formatWorkflowState(session.workflowState)}
                  </p>
                </div>
                <StatusBadge value={session.status} />
              </div>
              <p className="max-h-10 overflow-hidden text-xs leading-5 text-brand-gray">
                {session.lastAssistantText ?? 'No assistant response yet.'}
              </p>
              <p className="text-[11px] uppercase tracking-[0.12em] text-brand-gray">
                {formatDateTime(session.updatedAt)}
              </p>
            </div>
          )}
        </button>
      ))}
    </div>
  );
}
