import { API_BASE_URL } from '@/shared/config/env';
import type {
  ChatMessage,
  ChatSessionDetail,
  ChatSessionSummary,
  ChatTurnResponse,
  ContentBlock,
  CurrentUserContext,
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
    return request<ChatSessionSummary[]>('/chat/sessions', { profileId });
  },
  createChatSession(profileId: string, title?: string): Promise<ChatSessionDetail> {
    return request<ChatSessionDetail>('/chat/sessions', {
      method: 'POST',
      profileId,
      body: title ? { title } : {},
    });
  },
  getChatSession(profileId: string, sessionId: string): Promise<ChatSessionDetail> {
    return request<ChatSessionDetail>(`/chat/sessions/${sessionId}`, { profileId });
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
    });
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
    });
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

    yield { type: frame.event, ...payload } as TurnStreamEvent;
  }
}

function safeParseJson(raw: string): Record<string, unknown> | null {
  try {
    return JSON.parse(raw) as Record<string, unknown>;
  } catch {
    return null;
  }
}
