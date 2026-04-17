import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/features/auth/useAuthStore';
import { useApiModeStore } from '@/features/api-mode/useApiModeStore';
import type { ApiMode } from '@/shared/config/env';
import { cn } from '@/shared/lib/cn';

const OPTIONS: Array<{ mode: ApiMode; label: string; description: string }> = [
  {
    mode: 'mock',
    label: 'Mock',
    description: 'Use the in-browser demo dataset and local mock chat flow.',
  },
  {
    mode: 'backend',
    label: 'Backend',
    description: 'Use the Spring Boot API and persisted PostgreSQL-backed state.',
  },
];

export function ApiModeSwitch({
  compact = false,
  disabled = false,
  onModeChanged,
}: {
  compact?: boolean;
  disabled?: boolean;
  onModeChanged?: () => void;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const clearCurrentUser = useAuthStore((state) => state.clearCurrentUser);
  const apiMode = useApiModeStore((state) => state.apiMode);
  const setApiMode = useApiModeStore((state) => state.setApiMode);

  async function handleModeChange(nextMode: ApiMode) {
    if (disabled || nextMode === apiMode) {
      return;
    }

    await queryClient.cancelQueries();
    setApiMode(nextMode);
    clearCurrentUser();
    queryClient.clear();
    onModeChanged?.();
    navigate('/', { replace: true });
  }

  return (
    <div className={cn('border border-brand-line bg-white', compact ? 'p-3' : 'p-4')}>
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-brand-gray">Data Source</p>
          {!compact ? (
            <p className="mt-1 text-sm leading-6 text-brand-gray">
              Switch between browser mock data and the real backend without changing the page layout.
            </p>
          ) : null}
        </div>
        <span className="brand-chip">{apiMode}</span>
      </div>

      <div className={cn('grid grid-cols-2 gap-2', compact ? 'mt-3' : 'mt-4')}>
        {OPTIONS.map((option) => (
          <button
            key={option.mode}
            type="button"
            className={cn(
              'border px-3 py-3 text-left transition disabled:cursor-not-allowed disabled:opacity-45',
              apiMode === option.mode
                ? 'border-brand-black bg-brand-black text-white'
                : 'border-brand-line bg-brand-fog text-brand-black hover:border-brand-red hover:text-brand-red',
            )}
            onClick={() => {
              void handleModeChange(option.mode);
            }}
            aria-pressed={apiMode === option.mode}
            disabled={disabled}
          >
            <p className="text-sm font-semibold uppercase tracking-[0.08em]">{option.label}</p>
            {!compact ? <p className="mt-2 text-xs leading-5 opacity-80">{option.description}</p> : null}
          </button>
        ))}
      </div>
    </div>
  );
}
