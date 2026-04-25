import type { ChatMessage, UiEventRequest } from '@/shared/api/contracts';
import { BrandAvatar } from '@/shared/ui/BrandAvatar';
import { StructuredBlock } from '@/features/ui-events/StructuredBlocks';
import { formatDateTime } from '@/shared/lib/format';
import { cn } from '@/shared/lib/cn';

function processingMs(message: ChatMessage) {
  const raw = message.metadata?.processingMs;
  const value = typeof raw === 'number' ? raw : typeof raw === 'string' ? Number(raw) : null;
  return value !== null && Number.isFinite(value) && value >= 0 ? value : null;
}

function formatProcessingTime(ms: number) {
  if (ms < 1000) return `${Math.round(ms)} ms`;
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`;
}

export function MessageRenderer({
  message,
  assistantName,
  onSubmitUiEvent,
  disabled = false,
  entryIndex = 0,
}: {
  message: ChatMessage;
  assistantName: string;
  onSubmitUiEvent: (request: UiEventRequest) => void;
  disabled?: boolean;
  entryIndex?: number;
}) {
  const isUser = message.role === 'USER';
  const elapsedMs = processingMs(message);

  return (
    <div
      className={cn('brand-message-entry flex gap-4', isUser && 'justify-end')}
      style={{ animationDelay: `${Math.min(entryIndex * 40, 240)}ms` }}
    >
      {!isUser ? <BrandAvatar name={assistantName} size="sm" /> : null}
      <div className={cn('max-w-[840px] flex-1 space-y-3', isUser && 'flex max-w-[720px] flex-col items-end')}>
        <div className={cn('flex items-center gap-3', isUser && 'justify-end')}>
          <p className="text-sm font-semibold text-brand-black">{isUser ? 'You' : assistantName}</p>
          <p className="text-[11px] uppercase tracking-[0.12em] text-brand-gray">{formatDateTime(message.createdAt)}</p>
          {elapsedMs !== null ? (
            <p className="text-[11px] uppercase tracking-[0.12em] text-brand-gray">
              Processed in {formatProcessingTime(elapsedMs)}
            </p>
          ) : null}
        </div>

        {message.text && (!message.contentBlocks || message.contentBlocks.length === 0) ? (
          <div
            className={cn(
              'max-w-[720px] border px-5 py-4 text-sm leading-7',
              isUser ? 'border-brand-black bg-brand-black text-white' : 'border-brand-line bg-white text-brand-black',
            )}
          >
            {message.text}
          </div>
        ) : null}

        {message.contentBlocks?.map((block) => (
          <StructuredBlock
            key={block.blockId}
            messageId={message.messageId}
            block={block}
            onSubmit={onSubmitUiEvent}
            disabled={disabled}
          />
        ))}
      </div>
      {isUser ? <BrandAvatar name="You" size="sm" /> : null}
    </div>
  );
}
