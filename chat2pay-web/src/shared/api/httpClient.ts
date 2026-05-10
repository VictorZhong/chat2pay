import { API_BASE_URL } from '@/shared/config/env';
import type {
  ChatMessage,
  ChatSessionDetail,
  ChatSessionSummary,
  ChatTurnResponse,
  ContentBlock,
  CurrentUserContext,
  PaymentDraft,
  ProfileLoginRequest,
  ProfileSummary,
  SendMessageRequest,
  UiEventRequest,
} from '@/shared/api/contracts';
import { parseSseStream } from '@/shared/api/sse';

type JsonInit = Omit<RequestInit, 'body'> & { body?: unknown; profileId?: string };

function shouldLogChat(path: string) {
  return path.startsWith('/chat/');
}

function logChatHttp(label: string, details: Record<string, unknown>) {
  if (typeof console !== 'undefined') {
    console.debug(`[chat2pay] ${label}`, details);
  }
}

async function request<T>(path: string, init: JsonInit = {}): Promise<T> {
  const { body, profileId, headers, ...rest } = init;
  const url = `${API_BASE_URL}${path}`;
  const requestHeaders = {
    Accept: 'application/json',
    ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    ...(profileId ? { 'X-Profile-Id': profileId } : {}),
    ...(headers as Record<string, string> | undefined),
  };
  const requestBody = body !== undefined ? JSON.stringify(body) : undefined;

  if (shouldLogChat(path)) {
    logChatHttp('chat request', {
      method: rest.method ?? 'GET',
      url,
      profileId: profileId ?? null,
      headers: requestHeaders,
      body: body ?? null,
    });
  }

  const response = await fetch(url, {
    ...rest,
    headers: requestHeaders,
    body: requestBody,
  });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    if (shouldLogChat(path)) {
      logChatHttp('chat error response', {
        method: rest.method ?? 'GET',
        url,
        status: response.status,
        statusText: response.statusText,
        body: text,
      });
    }
    throw new Error(`HTTP ${response.status} ${response.statusText}: ${text}`);
  }

  if (response.status === 204) {
    if (shouldLogChat(path)) {
      logChatHttp('chat response', {
        method: rest.method ?? 'GET',
        url,
        status: response.status,
        statusText: response.statusText,
        body: null,
      });
    }
    return undefined as T;
  }

  const data = (await response.json()) as T;
  if (shouldLogChat(path)) {
    logChatHttp('chat response', {
      method: rest.method ?? 'GET',
      url,
      status: response.status,
      statusText: response.statusText,
      body: data,
    });
  }
  return data;
}

export const chat2payHttpClient = {
  listProfiles(): Promise<ProfileSummary[]> {
    return request<ProfileSummary[]>('/profiles');
  },
  profileLogin(payload: ProfileLoginRequest): Promise<CurrentUserContext> {
    return request<CurrentUserContext>('/auth/profile-login', {
      method: 'POST',
      body: payload,
    });
  },
  listChatSessions(profileId: string): Promise<ChatSessionSummary[]> {
    return request<ChatSessionSummary[]>('/chat/sessions', { profileId }).then((sessions) =>
      sessions.map(normalizeChatSessionSummary),
    );
  },
  createChatSession(profileId: string, title?: string): Promise<ChatSessionDetail> {
    return request<ChatSessionDetail>('/chat/sessions', {
      method: 'POST',
      profileId,
      body: title ? { title } : {},
    }).then(normalizeChatSessionDetail);
  },
  getChatSession(profileId: string, sessionId: string): Promise<ChatSessionDetail> {
    return request<ChatSessionDetail>(`/chat/sessions/${sessionId}`, { profileId }).then(
      normalizeChatSessionDetail,
    );
  },
  deleteChatSession(profileId: string, sessionId: string): Promise<void> {
    return request<void>(`/chat/sessions/${sessionId}`, {
      method: 'DELETE',
      profileId,
    });
  },
  renameChatSession(
    profileId: string,
    sessionId: string,
    title: string,
  ): Promise<ChatSessionDetail> {
    return request<ChatSessionDetail>(`/chat/sessions/${sessionId}`, {
      method: 'PATCH',
      profileId,
      body: { title },
    }).then(normalizeChatSessionDetail);
  },
  listChatMessages(profileId: string, sessionId: string): Promise<ChatMessage[]> {
    return request<ChatMessage[]>(`/chat/sessions/${sessionId}/messages`, { profileId });
  },
  sendChatMessage(
    profileId: string,
    sessionId: string,
    payload: SendMessageRequest,
  ): Promise<ChatTurnResponse> {
    return request<ChatTurnResponse>(`/chat/sessions/${sessionId}/messages`, {
      method: 'POST',
      profileId,
      body: { ...payload, stream: false },
    }).then(normalizeChatTurnResponse);
  },
  submitUiEvent(
    profileId: string,
    sessionId: string,
    payload: UiEventRequest,
  ): Promise<ChatTurnResponse> {
    return request<ChatTurnResponse>(`/chat/sessions/${sessionId}/events`, {
      method: 'POST',
      profileId,
      body: { ...payload, stream: false },
    }).then(normalizeChatTurnResponse);
  },
};

export type TurnStreamEvent =
  | { type: 'user-message'; message: ChatMessage }
  | { type: 'assistant-message-start'; messageId: string; sessionId: string }
  | { type: 'assistant-message-delta'; messageId: string; textDelta?: string; block?: ContentBlock }
  | { type: 'assistant-message-complete'; message: ChatMessage; session: ChatSessionDetail }
  | { type: 'turn-error'; code: string; message: string; processingMs?: number };

export async function* streamChatTurn(
  path: string,
  profileId: string,
  body: SendMessageRequest | UiEventRequest,
  signal?: AbortSignal,
): AsyncGenerator<TurnStreamEvent, void, void> {
  const url = `${API_BASE_URL}${path}`;
  const requestHeaders = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
    'X-Profile-Id': profileId,
  };
  const requestBody = { ...body, stream: true };

  logChatHttp('chat stream request', {
    method: 'POST',
    url,
    profileId,
    headers: requestHeaders,
    body: requestBody,
  });

  const response = await fetch(url, {
    method: 'POST',
    signal,
    headers: requestHeaders,
    body: JSON.stringify(requestBody),
  });

  if (!response.ok || !response.body) {
    const text = await response.text().catch(() => '');
    logChatHttp('chat stream error response', {
      method: 'POST',
      url,
      status: response.status,
      statusText: response.statusText,
      body: text,
    });
    throw new Error(`HTTP ${response.status} ${response.statusText}: ${text}`);
  }

  logChatHttp('chat stream response opened', {
    method: 'POST',
    url,
    status: response.status,
    statusText: response.statusText,
    contentType: response.headers.get('content-type'),
  });

  for await (const frame of parseSseStream(response.body, signal)) {
    if (!['user-message', 'assistant-message-start', 'assistant-message-delta', 'assistant-message-complete', 'turn-error'].includes(frame.event)) {
      continue;
    }

    const payload = safeParseJson(frame.data);
    if (!payload) continue;
    logChatHttp('chat stream event', {
      event: frame.event,
      payload,
    });

    const event = normalizeTurnStreamEvent({ type: frame.event, ...payload } as TurnStreamEvent);
    yield event;
    if (event.type === 'assistant-message-complete' || event.type === 'turn-error') {
      return;
    }
  }
}

function safeParseJson(raw: string): Record<string, unknown> | null {
  try {
    return JSON.parse(raw) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function normalizeAmount(value: unknown): number | null | undefined {
  if (value === null || value === undefined) return value;
  if (typeof value === 'number') return value;
  if (typeof value === 'string') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function normalizePaymentDraft<T extends PaymentDraft | null | undefined>(draft: T): T {
  if (!draft) return draft;
  return {
    ...draft,
    amount: normalizeAmount(draft.amount),
  } as T;
}

function normalizeChatSessionSummary(session: ChatSessionSummary): ChatSessionSummary {
  return session;
}

function normalizeChatSessionDetail(session: ChatSessionDetail): ChatSessionDetail {
  return {
    ...session,
    activeDraft: normalizePaymentDraft(session.activeDraft),
  };
}

function normalizeChatTurnResponse(turn: ChatTurnResponse): ChatTurnResponse {
  return {
    ...turn,
    session: normalizeChatSessionDetail(turn.session),
    activeDraft: normalizePaymentDraft(turn.activeDraft),
  };
}

function normalizeTurnStreamEvent(event: TurnStreamEvent): TurnStreamEvent {
  if (event.type === 'assistant-message-complete') {
    return {
      ...event,
      session: normalizeChatSessionDetail(event.session),
    };
  }
  return event;
}
