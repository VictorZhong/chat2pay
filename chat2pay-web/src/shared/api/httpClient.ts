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

async function request<T>(path: string, init: JsonInit = {}): Promise<T> {
  const { body, profileId, headers, ...rest } = init;
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...rest,
    headers: {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(profileId ? { 'X-Profile-Id': profileId } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`HTTP ${response.status} ${response.statusText}: ${text}`);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
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
  | { type: 'turn-error'; code: string; message: string };

export async function* streamChatTurn(
  path: string,
  profileId: string,
  body: SendMessageRequest | UiEventRequest,
  signal?: AbortSignal,
): AsyncGenerator<TurnStreamEvent, void, void> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      'X-Profile-Id': profileId,
    },
    body: JSON.stringify({ ...body, stream: true }),
  });

  if (!response.ok || !response.body) {
    const text = await response.text().catch(() => '');
    throw new Error(`HTTP ${response.status} ${response.statusText}: ${text}`);
  }

  for await (const frame of parseSseStream(response.body, signal)) {
    if (!['user-message', 'assistant-message-start', 'assistant-message-delta', 'assistant-message-complete', 'turn-error'].includes(frame.event)) {
      continue;
    }

    const payload = safeParseJson(frame.data);
    if (!payload) continue;

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
