import { afterEach, describe, expect, it, vi } from 'vitest';
import { streamChatTurn } from '@/shared/api/httpClient';
import type { ChatMessage, ChatSessionDetail } from '@/shared/api/contracts';

function assistantMessage(): ChatMessage {
  return {
    messageId: 'msg_assistant',
    sessionId: 'session_1',
    role: 'ASSISTANT',
    kind: 'BLOCKS',
    text: null,
    contentBlocks: [
      {
        blockId: 'blk_1',
        type: 'TEXT',
        title: 'Done',
        text: 'Choose an option.',
      },
    ],
    metadata: null,
    createdAt: '2026-05-10T00:00:00.000Z',
  };
}

function sessionDetail(): ChatSessionDetail {
  return {
    sessionId: 'session_1',
    title: 'Payment',
    titleLocked: false,
    status: 'ACTIVE',
    state: 'AWAITING_PAYEE_SELECTION',
    llmProvider: null,
    lastMessagePreview: 'Choose an option.',
    createdAt: '2026-05-10T00:00:00.000Z',
    updatedAt: '2026-05-10T00:00:01.000Z',
    activeDraft: null,
  };
}

function sseFrame(event: string, data: unknown) {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

describe('streamChatTurn', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('finishes when the terminal complete event is received', async () => {
    const encoder = new TextEncoder();
    const cancel = vi.fn();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(sseFrame('assistant-message-complete', {
          message: assistantMessage(),
          session: sessionDetail(),
        })));
      },
      cancel,
    });

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body,
      status: 200,
      statusText: 'OK',
      headers: new Headers({ 'content-type': 'text/event-stream' }),
    }));

    const iter = streamChatTurn('/chat/sessions/session_1/messages', 'profile_1', {
      messageText: 'Pay Lisa 100',
    });

    const first = await iter.next();
    expect(first.value).toMatchObject({ type: 'assistant-message-complete' });

    const done = await iter.next();
    expect(done.done).toBe(true);
    expect(cancel).toHaveBeenCalledTimes(1);
  });
});
