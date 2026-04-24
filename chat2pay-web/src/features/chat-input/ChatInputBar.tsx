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
    <div className="border-t border-brand-line bg-brand-fog px-3 py-2.5">
      <div className="flex items-center gap-2">
        <textarea
          className="brand-textarea brand-composer-textarea min-w-0 flex-1"
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
          rows={1}
        />
        <BrandButton
          className="h-[44px] shrink-0 px-5"
          onClick={() => void handleSend()}
          disabled={disabled || busy}
          loading={busy}
        >
          {busy ? 'Sending' : 'Send'}
        </BrandButton>
      </div>
    </div>
  );
}
