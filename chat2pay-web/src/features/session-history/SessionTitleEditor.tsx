import { useEffect, useRef, useState } from 'react';
import { CheckIcon, CloseIcon, PencilIcon } from '@/shared/ui/icons';

export function SessionTitleEditor({
  title,
  busy,
  onRename,
}: {
  title: string;
  busy?: boolean;
  onRename: (next: string) => Promise<unknown>;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(title);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    setDraft(title);
  }, [title]);

  useEffect(() => {
    if (editing) {
      inputRef.current?.focus();
      inputRef.current?.select();
    }
  }, [editing]);

  function cancel() {
    setDraft(title);
    setEditing(false);
  }

  async function commit() {
    const next = draft.trim();
    if (!next || next === title) {
      cancel();
      return;
    }
    try {
      await onRename(next);
      setEditing(false);
    } catch {
      // Leave the editor open; the mutation hook owns error surfaces.
    }
  }

  if (!editing) {
    return (
      <div className="flex items-center gap-2">
        <h2 className="text-lg font-semibold text-brand-black">{title}</h2>
        <button
          type="button"
          className="inline-flex h-7 w-7 items-center justify-center border border-transparent text-brand-gray transition hover:border-brand-red hover:text-brand-red"
          onClick={() => setEditing(true)}
          aria-label="Rename session"
          title="Rename session"
        >
          <PencilIcon className="h-4 w-4" />
        </button>
      </div>
    );
  }

  return (
    <form
      className="flex items-center gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        void commit();
      }}
    >
      <input
        ref={inputRef}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') cancel();
        }}
        maxLength={120}
        disabled={busy}
        className="border border-brand-line bg-white px-3 py-1 text-base font-semibold text-brand-black focus:border-brand-red focus:outline-none"
      />
      <button
        type="submit"
        className="inline-flex h-7 w-7 items-center justify-center border border-brand-line bg-white text-brand-black hover:border-brand-red hover:text-brand-red disabled:opacity-50"
        disabled={busy || !draft.trim()}
        aria-label="Save title"
        title="Save title"
      >
        <CheckIcon className="h-4 w-4" />
      </button>
      <button
        type="button"
        className="inline-flex h-7 w-7 items-center justify-center border border-brand-line bg-white text-brand-gray hover:border-brand-black hover:text-brand-black"
        onClick={cancel}
        disabled={busy}
        aria-label="Cancel rename"
        title="Cancel"
      >
        <CloseIcon className="h-4 w-4" />
      </button>
    </form>
  );
}
