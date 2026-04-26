import { useEffect } from 'react';
import type { ChatSessionSummary } from '@/shared/api/contracts';
import { BrandButton } from '@/shared/ui/BrandButton';
import { CloseIcon, TrashIcon } from '@/shared/ui/icons';

export function DeleteChatDialog({
  session,
  busy,
  onCancel,
  onConfirm,
}: {
  session: ChatSessionSummary | null;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  useEffect(() => {
    if (!session) return;

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !busy) {
        onCancel();
      }
    }

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [busy, onCancel, session]);

  if (!session) {
    return null;
  }

  return (
    <div className="brand-dialog-backdrop" role="presentation" onMouseDown={busy ? undefined : onCancel}>
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="delete-chat-title"
        aria-describedby="delete-chat-description"
        className="brand-dialog-panel w-full max-w-[480px]"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="border-b border-brand-line px-5 py-4">
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <p className="text-[11px] font-semibold uppercase tracking-[0.18em] text-brand-red">Delete chat</p>
              <h2 id="delete-chat-title" className="mt-1 text-lg font-semibold text-brand-black">
                Remove this conversation?
              </h2>
            </div>
            <button
              type="button"
              className="flex h-9 w-9 items-center justify-center border border-brand-line bg-white text-brand-gray transition hover:border-brand-black hover:text-brand-black disabled:cursor-not-allowed disabled:opacity-50"
              onClick={onCancel}
              disabled={busy}
              aria-label="Close delete confirmation"
            >
              <CloseIcon className="h-4 w-4" />
            </button>
          </div>
        </div>

        <div className="px-5 py-5">
          <div className="flex gap-4">
            <span className="flex h-11 w-11 shrink-0 items-center justify-center border border-brand-red bg-[#fff4f5] text-brand-red">
              <TrashIcon className="h-5 w-5" />
            </span>
            <div className="min-w-0">
              <p id="delete-chat-description" className="text-sm leading-7 text-brand-black">
                The chat history for this conversation will be deleted permanently.
              </p>
              <p className="mt-3 truncate border-l-4 border-brand-red bg-brand-fog px-3 py-2 text-sm font-semibold text-brand-black">
                {session.title}
              </p>
            </div>
          </div>
        </div>

        <div className="flex flex-col-reverse gap-3 border-t border-brand-line px-5 py-4 sm:flex-row sm:justify-end">
          <BrandButton type="button" variant="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </BrandButton>
          <BrandButton type="button" onClick={onConfirm} loading={busy} disabled={busy}>
            Delete
          </BrandButton>
        </div>
      </section>
    </div>
  );
}
