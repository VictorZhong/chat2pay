import type { ChatSessionSummary } from '@/shared/api/contracts';
import { formatIsoTimestamp, formatRelativeTime } from '@/shared/lib/format';
import { TrashIcon } from '@/shared/ui/icons';
import { cn } from '@/shared/lib/cn';

export function ChatHistoryList({
  sessions,
  selectedSessionId,
  onSelect,
  onDelete,
  collapsed,
  pendingDeleteSessionId,
}: {
  sessions: ChatSessionSummary[];
  selectedSessionId?: string;
  onSelect: (sessionId: string) => void;
  onDelete: (session: ChatSessionSummary) => void;
  collapsed: boolean;
  pendingDeleteSessionId?: string | null;
}) {
  return (
    <div className="space-y-2">
      {sessions.map((session) => {
        const isActive = selectedSessionId === session.sessionId;
        const isDeleting = pendingDeleteSessionId === session.sessionId;
        return (
          <div
            key={session.sessionId}
            className={cn(
              'brand-history-item relative w-full border transition',
              isActive
                ? 'brand-history-item-active border-brand-red bg-[#fff4f5]'
                : 'border-brand-line bg-white hover:border-brand-black',
              isDeleting && 'opacity-60',
            )}
          >
            <button
              type="button"
              className="block w-full px-4 py-4 text-left"
              onClick={() => onSelect(session.sessionId)}
              title={session.title}
            >
              {collapsed ? (
                <div className="space-y-2">
                  <div className="h-2 w-8 bg-brand-red" />
                  <div className="brand-status-indicator bg-brand-black text-brand-black" />
                </div>
              ) : (
                <div className="space-y-2 pr-7">
                  <p className="truncate text-sm font-semibold text-brand-black">{session.title}</p>
                  <p className="max-h-10 overflow-hidden text-xs leading-5 text-brand-gray">
                    {session.lastMessagePreview ?? 'No message yet.'}
                  </p>
                  <p
                    className="text-[11px] uppercase tracking-[0.12em] text-brand-gray"
                    title={formatIsoTimestamp(session.updatedAt)}
                  >
                    {formatRelativeTime(session.updatedAt)}
                  </p>
                </div>
              )}
            </button>
            {!collapsed ? (
              <button
                type="button"
                disabled={isDeleting}
                onClick={(event) => {
                  event.stopPropagation();
                  onDelete(session);
                }}
                title="Delete chat"
                aria-label={`Delete ${session.title}`}
                className="absolute right-2 top-2 inline-flex h-7 w-7 items-center justify-center border border-transparent text-brand-gray transition hover:border-brand-red hover:text-brand-red disabled:cursor-not-allowed"
              >
                <TrashIcon className="h-4 w-4" />
              </button>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}
