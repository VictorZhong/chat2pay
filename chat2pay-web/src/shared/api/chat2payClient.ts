import type { ProfileLoginRequest, SendMessageRequest, UiEventRequest } from '@/shared/api/contracts';
import { getApiMode } from '@/features/api-mode/useApiModeStore';
import { backendClient } from '@/shared/api/backendClient';
import * as mockServer from '@/shared/api/mockServer';
import type { ApiMode } from '@/shared/config/env';

interface Chat2PayClient {
  listProfiles: typeof mockServer.listProfiles;
  profileLogin: (payload: ProfileLoginRequest) => ReturnType<typeof mockServer.profileLogin>;
  listChatSessions: typeof mockServer.listChatSessions;
  createChatSession: typeof mockServer.createChatSession;
  getChatSession: typeof mockServer.getChatSession;
  listChatMessages: typeof mockServer.listChatMessages;
  sendChatMessage: (
    profileId: string,
    sessionId: string,
    payload: SendMessageRequest,
  ) => ReturnType<typeof mockServer.sendChatMessage>;
  submitUiEvent: (
    profileId: string,
    sessionId: string,
    payload: UiEventRequest,
  ) => ReturnType<typeof mockServer.submitUiEvent>;
  resetMockData: () => void | Promise<void>;
}

function resolveClient(apiMode: ApiMode = getApiMode()): Chat2PayClient {
  return apiMode === 'backend' ? backendClient : mockServer;
}

export const queryKeys = {
  profiles: (apiMode: ApiMode) => ['api', apiMode, 'profiles'] as const,
  sessions: (apiMode: ApiMode, profileId: string) => ['api', apiMode, 'sessions', profileId] as const,
  session: (apiMode: ApiMode, profileId: string, sessionId: string) =>
    ['api', apiMode, 'session', profileId, sessionId] as const,
  messages: (apiMode: ApiMode, profileId: string, sessionId: string) =>
    ['api', apiMode, 'messages', profileId, sessionId] as const,
};

export const chat2payClient = {
  listProfiles() {
    return resolveClient().listProfiles();
  },
  profileLogin(payload: ProfileLoginRequest) {
    return resolveClient().profileLogin(payload);
  },
  listChatSessions(profileId: string) {
    return resolveClient().listChatSessions(profileId);
  },
  createChatSession(profileId: string, title?: string) {
    return resolveClient().createChatSession(profileId, title);
  },
  getChatSession(profileId: string, sessionId: string) {
    return resolveClient().getChatSession(profileId, sessionId);
  },
  listChatMessages(profileId: string, sessionId: string) {
    return resolveClient().listChatMessages(profileId, sessionId);
  },
  sendChatMessage(profileId: string, sessionId: string, payload: SendMessageRequest) {
    return resolveClient().sendChatMessage(profileId, sessionId, payload);
  },
  submitUiEvent(profileId: string, sessionId: string, payload: UiEventRequest) {
    return resolveClient().submitUiEvent(profileId, sessionId, payload);
  },
  resetMockData() {
    return resolveClient().resetMockData();
  },
};
