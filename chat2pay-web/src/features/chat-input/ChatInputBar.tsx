import { useState } from 'react';
import { BrandButton } from '@/shared/ui/BrandButton';

export function ChatInputBar({
  disabled,
  busy,
  disabledReason,
  onEnableTextInput,
  onSend,
}: {
  disabled: boolean;
  busy: boolean;
  disabledReason?: string;
  onEnableTextInput?: () => void;
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
      {disabled && disabledReason ? (
        <div className="mb-2 flex flex-col gap-2 border border-brand-line bg-white px-3 py-2 text-xs text-brand-gray sm:flex-row sm:items-center sm:justify-between">
          <span>{disabledReason}</span>
          {onEnableTextInput ? (
            <button
              className="text-left font-semibold text-brand-red hover:underline sm:text-right"
              type="button"
              onClick={onEnableTextInput}
            >
              Use text instead
            </button>
          ) : null}
        </div>
      ) : null}
      <div className="flex items-center gap-2">
        <textarea
          className="brand-textarea brand-composer-textarea min-w-0 flex-1"
          placeholder={disabled && disabledReason ? disabledReason : "Type a transfer instruction, for example: Pay Tom 5000 HKD."}
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
