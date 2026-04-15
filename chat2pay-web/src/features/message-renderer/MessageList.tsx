import { useEffect, useRef } from 'react';
import type { ChatMessage, UiEventRequest } from '@/shared/api/contracts';
import { MessageRenderer } from '@/features/message-renderer/MessageRenderer';
import { BrandLoadingPanel } from '@/shared/ui/BrandLoadingPanel';

export function MessageList({
  messages,
  assistantName,
  onSubmitUiEvent,
  disabled = false,
  pendingUserText,
  showAssistantLoading = false,
}: {
  messages: ChatMessage[];
  assistantName: string;
  onSubmitUiEvent: (request: UiEventRequest) => void;
  disabled?: boolean;
  pendingUserText?: string;
  showAssistantLoading?: boolean;
}) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;

    if (!container) {
      return;
    }

    container.scrollTop = container.scrollHeight;
  }, [messages, pendingUserText, showAssistantLoading]);

  return (
    <div ref={containerRef} className="brand-message-surface brand-scrollbar min-h-0 flex-1 overflow-y-auto">
      <div className="space-y-8 px-6 py-6">
        {messages.map((message) => (
          <MessageRenderer
            key={message.messageId}
            message={message}
            assistantName={assistantName}
            onSubmitUiEvent={onSubmitUiEvent}
            disabled={disabled}
          />
        ))}
        {pendingUserText ? (
          <div className="flex justify-end gap-4">
            <div className="max-w-[720px] border border-brand-red bg-[#fff4f5] px-5 py-4 text-sm leading-7 text-brand-black">
              <div className="mb-2 flex items-center justify-between gap-3">
                <p className="text-xs font-semibold uppercase tracking-[0.14em] text-brand-red">Pending</p>
                <span className="brand-loading-track brand-loading-track-compact">
                  <span className="brand-loading-bar" />
                  <span className="brand-loading-bar" />
                  <span className="brand-loading-bar" />
                </span>
              </div>
              {pendingUserText}
            </div>
          </div>
        ) : null}
        {showAssistantLoading ? <BrandLoadingPanel compact /> : null}
      </div>
    </div>
  );
}
