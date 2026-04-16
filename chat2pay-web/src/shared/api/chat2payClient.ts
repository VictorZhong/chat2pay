import type { ProfileLoginRequest, SendMessageRequest, UiEventRequest } from '@/shared/api/contracts';
import { USE_MOCK_API } from '@/shared/config/env';
import * as mockServer from '@/shared/api/mockServer';

function assertMockMode() {
  if (!USE_MOCK_API) {
    throw new Error('Real API client is not wired yet.');
  }
}

export const queryKeys = {
  profiles: ['profiles'] as const,
  sessions: (profileId: string) => ['sessions', profileId] as const,
  session: (profileId: string, sessionId: string) => ['session', profileId, sessionId] as const,
  messages: (profileId: string, sessionId: string) => ['messages', profileId, sessionId] as const,
};

export const chat2payClient = {
  listProfiles() {
    assertMockMode();
    return mockServer.listProfiles();
  },
  profileLogin(payload: ProfileLoginRequest) {
    assertMockMode();
    return mockServer.profileLogin(payload);
  },
  listChatSessions(profileId: string) {
    assertMockMode();
    return mockServer.listChatSessions(profileId);
  },
  createChatSession(profileId: string, title?: string) {
    assertMockMode();
    return mockServer.createChatSession(profileId, title);
  },
  getChatSession(profileId: string, sessionId: string) {
    assertMockMode();
    return mockServer.getChatSession(profileId, sessionId);
  },
  listChatMessages(profileId: string, sessionId: string) {
    assertMockMode();
    return mockServer.listChatMessages(profileId, sessionId);
  },
  sendChatMessage(profileId: string, sessionId: string, payload: SendMessageRequest) {
    assertMockMode();
    return mockServer.sendChatMessage(profileId, sessionId, payload);
  },
  submitUiEvent(profileId: string, sessionId: string, payload: UiEventRequest) {
    assertMockMode();
    return mockServer.submitUiEvent(profileId, sessionId, payload);
  },
  resetMockData() {
    assertMockMode();
    return mockServer.resetMockData();
  },
};
