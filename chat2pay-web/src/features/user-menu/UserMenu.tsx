import { DownOutlined, LogoutOutlined } from '@ant-design/icons';
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
          </div>
        </div>
        <BrandButton fullWidth onClick={onLogout}>
          <LogoutOutlined />
          Logout
        </BrandButton>
      </div>
    </div>
  );

  return (
    <Popover trigger="click" placement="topLeft" content={content}>
      <button className="flex w-full items-center gap-3 border border-[#3a3a3a] bg-[linear-gradient(180deg,#1a1a1a_0%,#262626_100%)] px-4 py-2.5 text-left text-white transition hover:border-brand-red hover:bg-[linear-gradient(180deg,#1d1d1d_0%,#2c2c2c_100%)]">
        <BrandAvatar name={user.displayName} size="sm" />
        {!collapsed ? (
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold text-white">{user.displayName}</p>
          </div>
        ) : null}
        {!collapsed ? <DownOutlined className="ml-auto text-[12px] text-[#c9c9c9]" /> : null}
      </button>
    </Popover>
  );
}
