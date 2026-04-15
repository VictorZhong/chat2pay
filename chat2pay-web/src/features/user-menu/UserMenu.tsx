import { useEffect, useRef, useState } from 'react';
import type { CurrentUserContext } from '@/shared/api/contracts';
import { BrandAvatar } from '@/shared/ui/BrandAvatar';
import { BrandButton } from '@/shared/ui/BrandButton';
import { DownIcon, LogoutIcon } from '@/shared/ui/icons';

export function UserMenu({
  user,
  collapsed,
  onLogout,
}: {
  user: CurrentUserContext;
  collapsed: boolean;
  onLogout: () => void;
}) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handlePointerDown(event: MouseEvent) {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }

    function handleEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    }

    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleEscape);

    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleEscape);
    };
  }, []);

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
          <LogoutIcon className="h-4 w-4" />
          Logout
        </BrandButton>
      </div>
    </div>
  );

  return (
    <div ref={containerRef} className="relative">
      <button
        className="flex w-full items-center gap-3 border border-[#3a3a3a] bg-[linear-gradient(180deg,#1a1a1a_0%,#262626_100%)] px-4 py-2.5 text-left text-white transition hover:border-brand-red hover:bg-[linear-gradient(180deg,#1d1d1d_0%,#2c2c2c_100%)]"
        aria-label="Open profile menu"
        onClick={() => setOpen((current) => !current)}
      >
        <BrandAvatar name={user.displayName} size="sm" />
        {!collapsed ? (
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold text-white">{user.displayName}</p>
          </div>
        ) : null}
        {!collapsed ? <DownIcon className="ml-auto h-3 w-3 text-[#c9c9c9]" /> : null}
      </button>
      {open ? <div className="absolute bottom-[calc(100%+8px)] left-0 z-30">{content}</div> : null}
    </div>
  );
}
