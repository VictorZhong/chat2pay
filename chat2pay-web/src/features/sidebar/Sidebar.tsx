import type { CurrentUserContext, ChatSessionSummary } from '@/shared/api/contracts';
import { BrandButton } from '@/shared/ui/BrandButton';
import { ChatHistoryList } from '@/features/session-history/ChatHistoryList';
import { UserMenu } from '@/features/user-menu/UserMenu';
import { cn } from '@/shared/lib/cn';
import {
  BankIcon,
  HistoryIcon,
  MenuFoldIcon,
  MenuUnfoldIcon,
  PlusIcon,
  TeamIcon,
} from '@/shared/ui/icons';

const PLACEHOLDERS = [
  { label: 'My Account', icon: BankIcon },
  { label: 'My Payee', icon: TeamIcon },
  { label: 'Transaction History', icon: HistoryIcon },
];

export function Sidebar({
  user,
  sessions,
  selectedSessionId,
  collapsed,
  onToggleCollapsed,
  onNewChat,
  onSelectSession,
  onLogout,
}: {
  user: CurrentUserContext;
  sessions: ChatSessionSummary[];
  selectedSessionId?: string;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  onNewChat: () => void;
  onSelectSession: (sessionId: string) => void;
  onLogout: () => void;
}) {
  return (
    <aside
      className={cn(
        'brand-panel brand-sidebar-shell flex max-h-[42vh] w-full flex-col bg-[#fcfcfc] lg:max-h-none',
        collapsed ? 'lg:w-[108px]' : 'lg:w-[360px]',
      )}
    >
      <div className="border-b border-brand-line px-4 py-4">
        <div className="mb-4 flex items-center justify-between gap-3">
          {!collapsed ? (
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.18em] text-brand-red">chat2pay</p>
              <h1 className="mt-1 text-lg font-semibold text-brand-black">Payment workspace</h1>
            </div>
          ) : (
            <div className="h-10 w-10 border border-brand-black bg-brand-red" />
          )}
          <button
            className="flex h-10 w-10 items-center justify-center border border-brand-line bg-white text-sm font-semibold hover:border-brand-black"
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

      <div className="border-b border-brand-line px-4 py-5">
        <div className="space-y-2">
          {PLACEHOLDERS.map((item) => {
            const Icon = item.icon;

            return (
              <div
                key={item.label}
                className={cn(
                  'brand-sidebar-nav-item flex items-center gap-3 border border-dashed border-brand-line bg-white px-4 py-3 text-sm text-brand-gray',
                  collapsed && 'justify-center px-0',
                )}
                title={collapsed ? item.label : undefined}
              >
                <Icon className="h-4 w-4 text-brand-black" />
                {!collapsed ? <span>{item.label}</span> : null}
              </div>
            );
          })}
        </div>
      </div>

      <div className="min-h-0 flex-1 px-4 py-5">
        {!collapsed ? (
          <div className="mb-4 flex items-center justify-between">
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-brand-gray">Chat History</p>
            <span className="text-xs text-brand-gray">{sessions.length}</span>
          </div>
        ) : null}
        <div className="brand-scrollbar max-h-[22vh] overflow-y-auto pr-1 lg:h-full lg:max-h-none">
          <ChatHistoryList
            sessions={sessions}
            selectedSessionId={selectedSessionId}
            onSelect={onSelectSession}
            collapsed={collapsed}
          />
        </div>
      </div>

      <div className="mt-auto border-t border-brand-black bg-[linear-gradient(180deg,#141414_0%,#202020_100%)] p-4">
        <UserMenu user={user} collapsed={collapsed} onLogout={onLogout} />
      </div>
    </aside>
  );
}
