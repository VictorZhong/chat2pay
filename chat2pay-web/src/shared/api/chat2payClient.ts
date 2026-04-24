import type { ProfileLoginRequest, SendMessageRequest, UiEventRequest } from '@/shared/api/contracts';
import { USE_MOCK_API } from '@/shared/config/env';
import * as mockServer from '@/shared/api/mockServer';
import { chat2payHttpClient, streamChatTurn } from '@/shared/api/httpClient';

export const queryKeys = {
  profiles: ['profiles'] as const,
  sessions: (profileId: string) => ['sessions', profileId] as const,
  session: (profileId: string, sessionId: string) => ['session', profileId, sessionId] as const,
  messages: (profileId: string, sessionId: string) => ['messages', profileId, sessionId] as const,
};

export const chat2payClient = {
  listProfiles() {
    return USE_MOCK_API ? mockServer.listProfiles() : chat2payHttpClient.listProfiles();
  },
  profileLogin(payload: ProfileLoginRequest) {
    return USE_MOCK_API ? mockServer.profileLogin(payload) : chat2payHttpClient.profileLogin(payload);
  },
  listChatSessions(profileId: string) {
    return USE_MOCK_API
      ? mockServer.listChatSessions(profileId)
      : chat2payHttpClient.listChatSessions(profileId);
  },
  createChatSession(profileId: string, title?: string) {
    return USE_MOCK_API
      ? mockServer.createChatSession(profileId, title)
      : chat2payHttpClient.createChatSession(profileId, title);
  },
  getChatSession(profileId: string, sessionId: string) {
    return USE_MOCK_API
      ? mockServer.getChatSession(profileId, sessionId)
      : chat2payHttpClient.getChatSession(profileId, sessionId);
  },
  listChatMessages(profileId: string, sessionId: string) {
    return USE_MOCK_API
      ? mockServer.listChatMessages(profileId, sessionId)
      : chat2payHttpClient.listChatMessages(profileId, sessionId);
  },
  sendChatMessage(profileId: string, sessionId: string, payload: SendMessageRequest) {
    return USE_MOCK_API
      ? mockServer.sendChatMessage(profileId, sessionId, payload)
      : chat2payHttpClient.sendChatMessage(profileId, sessionId, payload);
  },
  submitUiEvent(profileId: string, sessionId: string, payload: UiEventRequest) {
    return USE_MOCK_API
      ? mockServer.submitUiEvent(profileId, sessionId, payload)
      : chat2payHttpClient.submitUiEvent(profileId, sessionId, payload);
  },
  streamChatMessage(
    profileId: string,
    sessionId: string,
    payload: SendMessageRequest,
    signal?: AbortSignal,
  ) {
    if (USE_MOCK_API) {
      throw new Error('SSE streaming is only available against the real backend.');
    }
    return streamChatTurn(`/chat/sessions/${sessionId}/messages`, profileId, payload, signal);
  },
  streamUiEvent(
    profileId: string,
    sessionId: string,
    payload: UiEventRequest,
    signal?: AbortSignal,
  ) {
    if (USE_MOCK_API) {
      throw new Error('SSE streaming is only available against the real backend.');
    }
    return streamChatTurn(`/chat/sessions/${sessionId}/events`, profileId, payload, signal);
  },
  resetMockData() {
    if (!USE_MOCK_API) {
      throw new Error('resetMockData is only available in mock mode.');
    }
    return mockServer.resetMockData();
  },
};
