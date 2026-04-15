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
      <div className="grid gap-4 lg:grid-cols-[1fr_auto] lg:items-end">
        <div>
          <label className="mb-2 block text-xs font-semibold uppercase tracking-[0.16em] text-brand-gray">
            Message
          </label>
          <textarea
            className="brand-textarea"
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
        <div className="flex flex-col gap-3">
          <BrandButton
            className="min-w-[164px]"
            onClick={() => void handleSend()}
            disabled={disabled || busy}
            loading={busy}
          >
            {busy ? 'Awaiting Response' : 'Send'}
          </BrandButton>
        </div>
      </div>
    </div>
  );
}
