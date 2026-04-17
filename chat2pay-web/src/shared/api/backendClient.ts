import type {
  ChatMessagePage,
  ChatSessionCreateResponse,
  ChatSessionDetail,
  ChatSessionSummaryPage,
  ChatTurnResponse,
  CurrentUserContext,
  ErrorResponse,
  ProfileLoginRequest,
  ProfileSummary,
  SendMessageRequest,
  UiEventRequest,
} from '@/shared/api/contracts';
import { BACKEND_API_BASE_URL } from '@/shared/config/env';

interface RequestOptions {
  body?: unknown;
  method?: 'GET' | 'POST';
  profileId?: string;
}

function buildUrl(path: string) {
  return `${BACKEND_API_BASE_URL}${path}`;
}

async function parseError(response: Response) {
  let message = `Request failed with status ${response.status}.`;

  try {
    const payload = (await response.json()) as Partial<ErrorResponse>;
    if (typeof payload.message === 'string' && payload.message.trim()) {
      message = payload.message;
    }
  } catch {
    // Ignore parse errors and fall back to the generic message.
  }

  return new Error(message);
}

async function requestJson<T>(path: string, options: RequestOptions = {}) {
  const headers = new Headers();

  if (options.profileId) {
    headers.set('X-Profile-Id', options.profileId);
  }

  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(buildUrl(path), {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });

  if (!response.ok) {
    throw await parseError(response);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

export const backendClient = {
  listProfiles() {
    return requestJson<ProfileSummary[]>('/api/profiles');
  },
  profileLogin(payload: ProfileLoginRequest) {
    return requestJson<CurrentUserContext>('/api/auth/profile-login', {
      method: 'POST',
      body: payload,
    });
  },
  currentUser(profileId: string) {
    return requestJson<CurrentUserContext>('/api/me', { profileId });
  },
  logout(profileId: string) {
    return requestJson<void>('/api/auth/logout', {
      method: 'POST',
      profileId,
    });
  },
  listChatSessions(profileId: string) {
    return requestJson<ChatSessionSummaryPage>('/api/chat/sessions', { profileId });
  },
  createChatSession(profileId: string, title?: string) {
    return requestJson<ChatSessionCreateResponse>('/api/chat/sessions', {
      method: 'POST',
      profileId,
      body: title ? { title } : undefined,
    });
  },
  getChatSession(profileId: string, sessionId: string) {
    return requestJson<ChatSessionDetail>(`/api/chat/sessions/${encodeURIComponent(sessionId)}`, {
      profileId,
    });
  },
  listChatMessages(profileId: string, sessionId: string) {
    return requestJson<ChatMessagePage>(
      `/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
      {
        profileId,
      },
    );
  },
  sendChatMessage(profileId: string, sessionId: string, payload: SendMessageRequest) {
    return requestJson<ChatTurnResponse>(
      `/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
      {
        method: 'POST',
        profileId,
        body: payload,
      },
    );
  },
  submitUiEvent(profileId: string, sessionId: string, payload: UiEventRequest) {
    return requestJson<ChatTurnResponse>(
      `/api/chat/sessions/${encodeURIComponent(sessionId)}/events`,
      {
        method: 'POST',
        profileId,
        body: payload,
      },
    );
  },
  resetMockData() {
    return Promise.resolve();
  },
};
