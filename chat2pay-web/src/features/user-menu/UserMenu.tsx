import { Popover } from 'antd';
import type { CurrentUserContext } from '@/shared/api/contracts';
import { BrandAvatar } from '@/shared/ui/BrandAvatar';
import { BrandButton } from '@/shared/ui/BrandButton';

export function UserMenu({
  user,
  collapsed,
  onLogout,
}: {
  user: CurrentUserContext;
  collapsed: boolean;
  onLogout: () => void;
}) {
  const content = (
    <div className="w-64 bg-white p-1">
      <div className="border border-brand-line p-4">
        <div className="mb-4 flex items-center gap-3">
          <BrandAvatar name={user.displayName} size="sm" />
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-brand-black">{user.displayName}</p>
            <p className="truncate text-xs uppercase tracking-[0.14em] text-brand-gray">{user.username}</p>
          </div>
        </div>
        <BrandButton fullWidth onClick={onLogout}>
          Logout
        </BrandButton>
      </div>
    </div>
  );

  return (
    <Popover trigger="click" placement="topLeft" content={content}>
      <button className="flex w-full items-center gap-3 border border-brand-line bg-white px-4 py-4 text-left transition hover:border-brand-red hover:bg-[#fff4f5]">
        <BrandAvatar name={user.displayName} size="sm" />
        {!collapsed ? (
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-brand-black">{user.username}</p>
            <p className="truncate text-xs uppercase tracking-[0.14em] text-brand-gray">{user.displayName}</p>
          </div>
        ) : null}
      </button>
    </Popover>
  );
}
