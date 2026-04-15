import { useEffect, useRef } from 'react';
import type { ChatMessage, UiEventRequest } from '@/shared/api/contracts';
import { MessageRenderer } from '@/features/message-renderer/MessageRenderer';

export function MessageList({
  messages,
  assistantName,
  onSubmitUiEvent,
}: {
  messages: ChatMessage[];
  assistantName: string;
  onSubmitUiEvent: (request: UiEventRequest) => void;
}) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const container = containerRef.current;

    if (!container) {
      return;
    }

    container.scrollTop = container.scrollHeight;
  }, [messages]);

  return (
    <div ref={containerRef} className="brand-scrollbar min-h-0 flex-1 overflow-y-auto">
      <div className="space-y-8 px-6 py-6">
        {messages.map((message) => (
          <MessageRenderer
            key={message.messageId}
            message={message}
            assistantName={assistantName}
            onSubmitUiEvent={onSubmitUiEvent}
          />
        ))}
      </div>
    </div>
  );
}
