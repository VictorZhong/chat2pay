import { useState } from 'react';
import { BrandButton } from '@/shared/ui/BrandButton';

export function ChatInputBar({
  disabled,
  busy,
  onSend,
}: {
  disabled: boolean;
  busy: boolean;
  onSend: (messageText: string) => Promise<void> | void;
}) {
  const [value, setValue] = useState('');

  async function handleSend() {
    const nextValue = value.trim();

    if (!nextValue || disabled || busy) {
      return;
    }

    await onSend(nextValue);
    setValue('');
  }

  return (
    <div className="brand-panel p-4">
      <label className="mb-3 block text-xs font-semibold uppercase tracking-[0.16em] text-brand-gray">
        Message
      </label>
      <div className="flex items-end gap-3">
        <div className="min-w-0 flex-1">
          <textarea
            className="brand-textarea brand-composer-textarea"
            placeholder="Type a transfer instruction, for example: Pay Tom 5000 HKD."
            value={value}
            onChange={(event) => setValue(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                void handleSend();
              }
            }}
            disabled={disabled || busy}
          />
        </div>
        <BrandButton
          className="h-[44px] min-w-[164px] shrink-0"
          onClick={() => void handleSend()}
          disabled={disabled || busy}
          loading={busy}
        >
          {busy ? 'Awaiting Response' : 'Send'}
        </BrandButton>
      </div>
      <p className="mt-3 text-[11px] uppercase tracking-[0.12em] text-brand-gray">
        Enter sends. Shift+Enter adds a new line.
      </p>
    </div>
  );
}
