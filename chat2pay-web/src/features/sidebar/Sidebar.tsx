import type { CurrentUserContext, ChatSessionSummary } from '@/shared/api/contracts';
import { BrandButton } from '@/shared/ui/BrandButton';
import { ChatHistoryList } from '@/features/session-history/ChatHistoryList';
import { UserMenu } from '@/features/user-menu/UserMenu';
import { cn } from '@/shared/lib/cn';
import { BrandMark } from '@/shared/ui/BrandMark';
import {
  BankIcon,
  MenuFoldIcon,
  MenuUnfoldIcon,
  PlusIcon,
  TeamIcon,
} from '@/shared/ui/icons';

const QUICK_ACTIONS = [
  { label: 'My Accounts', icon: BankIcon, prompt: 'List my accounts' },
  { label: 'My Payees', icon: TeamIcon, prompt: 'List my payees' },
];

export function Sidebar({
  user,
  sessions,
  selectedSessionId,
  collapsed,
  onToggleCollapsed,
  onNewChat,
  onSelectSession,
  onDeleteSession,
  onQuickAction,
  onLogout,
  pendingDeleteSessionId,
}: {
  user: CurrentUserContext;
  sessions: ChatSessionSummary[];
  selectedSessionId?: string;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  onNewChat: () => void;
  onSelectSession: (sessionId: string) => void;
  onDeleteSession: (session: ChatSessionSummary) => void;
  onQuickAction: (prompt: string) => void;
  onLogout: () => void;
  pendingDeleteSessionId?: string | null;
}) {
  return (
    <aside
      className={cn(
        'brand-panel brand-sidebar-shell flex max-h-[42vh] w-full flex-col bg-[#fcfcfc] lg:max-h-none',
        collapsed ? 'lg:w-[96px]' : 'lg:w-[320px]',
      )}
    >
      <div className="border-b border-brand-line px-3 py-3">
        <div className="mb-3 flex items-center justify-between gap-2">
          {!collapsed ? (
            <div className="flex min-w-0 items-center gap-3">
              <BrandMark size="sm" />
              <div className="min-w-0">
                <p className="text-[11px] font-semibold uppercase tracking-[0.18em] text-brand-red">chat2pay</p>
                <h1 className="mt-0.5 truncate text-base font-semibold text-brand-black">Payment workspace</h1>
              </div>
            </div>
          ) : (
            <BrandMark size="sm" />
          )}
          <button
            className="flex h-9 w-9 items-center justify-center border border-brand-line bg-white text-sm font-semibold hover:border-brand-black"
            onClick={onToggleCollapsed}
            aria-label="Toggle sidebar"
          >
            {collapsed ? <MenuUnfoldIcon className="h-4 w-4" /> : <MenuFoldIcon className="h-4 w-4" />}
          </button>
        </div>
        <BrandButton fullWidth onClick={onNewChat}>
          <PlusIcon className="h-4 w-4" />
          {collapsed ? '' : 'New Chat'}
        </BrandButton>
      </div>

      <div className="border-b border-brand-line px-3 py-3">
        <div className="space-y-1.5">
          {QUICK_ACTIONS.map((item) => {
            const Icon = item.icon;

            return (
              <button
                key={item.label}
                type="button"
                onClick={() => onQuickAction(item.prompt)}
                className={cn(
                  'brand-sidebar-nav-item flex w-full items-center gap-3 border border-brand-line bg-white px-3 py-2 text-sm text-brand-black transition hover:border-brand-red hover:text-brand-red',
                  collapsed && 'justify-center px-0',
                )}
                title={collapsed ? item.label : undefined}
              >
                <Icon className="h-4 w-4" />
                {!collapsed ? <span>{item.label}</span> : null}
              </button>
            );
          })}
        </div>
      </div>

      <div className="min-h-0 flex-1 px-3 py-3">
        {!collapsed ? (
          <div className="mb-2 flex items-center justify-between">
            <p className="text-[11px] font-semibold uppercase tracking-[0.18em] text-brand-gray">Chat History</p>
            <span className="text-xs text-brand-gray">{sessions.length}</span>
          </div>
        ) : null}
        <div className="brand-scrollbar max-h-[22vh] overflow-y-auto pr-1 lg:h-full lg:max-h-none">
          <ChatHistoryList
            sessions={sessions}
            selectedSessionId={selectedSessionId}
            onSelect={onSelectSession}
            onDelete={onDeleteSession}
            collapsed={collapsed}
            pendingDeleteSessionId={pendingDeleteSessionId}
          />
        </div>
      </div>

      <div className="mt-auto border-t border-brand-black bg-[linear-gradient(180deg,#141414_0%,#202020_100%)] p-3">
        <UserMenu user={user} collapsed={collapsed} onLogout={onLogout} />
      </div>
    </aside>
  );
}
