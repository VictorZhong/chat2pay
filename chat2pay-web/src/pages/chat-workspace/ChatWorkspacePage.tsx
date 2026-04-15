import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import type { ChatMessage, ContentBlock, UiEventRequest } from '@/shared/api/contracts';
import { chat2payClient, queryKeys } from '@/shared/api/chat2payClient';
import { useAuthStore } from '@/features/auth/useAuthStore';
import { useSidebarStore } from '@/features/sidebar/useSidebarStore';
import { Sidebar } from '@/features/sidebar/Sidebar';
import { MessageList } from '@/features/message-renderer/MessageList';
import { ChatInputBar } from '@/features/chat-input/ChatInputBar';
import { EmptyStatePanel } from '@/shared/ui/EmptyStatePanel';
import { BrandButton } from '@/shared/ui/BrandButton';
import { BrandLoadingPanel } from '@/shared/ui/BrandLoadingPanel';
import { StatusBadge } from '@/shared/ui/StatusBadge';
import { WorkflowOverview } from '@/shared/ui/WorkflowOverview';
import { INTERACTION_DELAY_MS } from '@/shared/config/env';
import { wait } from '@/shared/lib/time';

function actionLabelFromSummaryBlock(block: ContentBlock | undefined, actionId: string) {
  if (!block || block.type !== 'SUMMARY_CARD') {
    return actionId;
  }

  const actions = Array.isArray(block.metadata?.actions) ? block.metadata.actions : [];
  const matched = actions.find(
    (item): item is { id: string; label: string } =>
      typeof item === 'object' &&
      item !== null &&
      'id' in item &&
      'label' in item &&
      item.id === actionId &&
      typeof item.label === 'string',
  );

  return matched?.label ?? actionId;
}

function derivePendingUiEventText(messages: ChatMessage[], payload: UiEventRequest) {
  if (payload.eventType === 'SUBMIT_FORM') {
    return 'Submitted transfer details';
  }

  const sourceMessage = messages.find((message) => message.messageId === payload.sourceMessageId);
  const sourceBlock = sourceMessage?.contentBlocks?.find((block) => block.blockId === payload.sourceBlockId);
  const selectedId = payload.selectedItemIds?.[0];

  if (!selectedId) {
    return 'Submitted action';
  }

  if (payload.eventType === 'CLICK_ACTION') {
    return actionLabelFromSummaryBlock(sourceBlock, selectedId);
  }

  if (sourceBlock?.type === 'SELECTABLE_LIST') {
    const item = sourceBlock.items.find((entry) => entry.itemId === selectedId);
    return item ? `Selected ${item.label}` : 'Selected an option';
  }

  return 'Submitted action';
}

export function ChatWorkspacePage() {
  const navigate = useNavigate();
  const { sessionId } = useParams();
  const queryClient = useQueryClient();
  const currentUser = useAuthStore((state) => state.currentUser)!;
  const clearCurrentUser = useAuthStore((state) => state.clearCurrentUser);
  const collapsed = useSidebarStore((state) => state.collapsed);
  const setCollapsed = useSidebarStore((state) => state.setCollapsed);

  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions(currentUser.profileId),
    queryFn: () => chat2payClient.listChatSessions(currentUser.profileId),
  });

  const sessionQuery = useQuery({
    queryKey: queryKeys.session(currentUser.profileId, sessionId ?? ''),
    queryFn: () => chat2payClient.getChatSession(currentUser.profileId, sessionId ?? ''),
    enabled: Boolean(sessionId),
  });

  const messagesQuery = useQuery({
    queryKey: queryKeys.messages(currentUser.profileId, sessionId ?? ''),
    queryFn: () => chat2payClient.listChatMessages(currentUser.profileId, sessionId ?? ''),
    enabled: Boolean(sessionId),
  });

  async function refreshCurrentSession(nextSessionId: string) {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.sessions(currentUser.profileId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.session(currentUser.profileId, nextSessionId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.messages(currentUser.profileId, nextSessionId) }),
    ]);
  }

  const createSessionMutation = useMutation({
    mutationFn: () => chat2payClient.createChatSession(currentUser.profileId),
    onSuccess: async (response) => {
      navigate(`/chat/${response.session.sessionId}`);
      await refreshCurrentSession(response.session.sessionId);
    },
  });

  const sendMessageMutation = useMutation({
    mutationFn: async (messageText: string) => {
      await wait(INTERACTION_DELAY_MS);
      return chat2payClient.sendChatMessage(currentUser.profileId, sessionId ?? '', { messageText });
    },
    onSuccess: async () => {
      if (sessionId) {
        await refreshCurrentSession(sessionId);
      }
    },
  });

  const submitUiEventMutation = useMutation({
    mutationFn: async (payload: UiEventRequest) => {
      await wait(INTERACTION_DELAY_MS);
      return chat2payClient.submitUiEvent(currentUser.profileId, sessionId ?? '', payload);
    },
    onSuccess: async () => {
      if (sessionId) {
        await refreshCurrentSession(sessionId);
      }
    },
  });

  const sessions = sessionsQuery.data?.items ?? [];
  const activeSession = sessionQuery.data;
  const messages = messagesQuery.data?.items ?? [];
  const busy = createSessionMutation.isPending || sendMessageMutation.isPending || submitUiEventMutation.isPending;
  const readOnly = !activeSession || activeSession.status !== 'ACTIVE';
  const pendingUserText = sendMessageMutation.isPending
    ? sendMessageMutation.variables
    : submitUiEventMutation.isPending
      ? derivePendingUiEventText(messages, submitUiEventMutation.variables)
      : undefined;
  const showAssistantLoading = sendMessageMutation.isPending || submitUiEventMutation.isPending;

  return (
    <main className="brand-shell flex min-h-screen flex-col gap-5 bg-transparent p-4 lg:h-screen lg:flex-row lg:p-5">
      <Sidebar
        user={currentUser}
        sessions={sessions}
        selectedSessionId={sessionId}
        collapsed={collapsed}
        onToggleCollapsed={() => setCollapsed(!collapsed)}
        onNewChat={() => createSessionMutation.mutate()}
        onSelectSession={(selectedSessionId) => navigate(`/chat/${selectedSessionId}`)}
        onLogout={() => {
          clearCurrentUser();
          navigate('/');
        }}
      />

      <section className="flex min-w-0 flex-1 flex-col gap-5">
        <header className="brand-panel flex flex-col gap-4 px-5 py-5 sm:flex-row sm:items-center sm:justify-between sm:px-6">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.2em] text-brand-red">Session</p>
            <h2 className="mt-2 text-2xl font-semibold text-brand-black">
              {activeSession?.title ?? 'Conversation workspace'}
            </h2>
          </div>

          {activeSession ? (
            <div className="flex flex-wrap items-center gap-3">
              <StatusBadge value={activeSession.status} />
              <WorkflowOverview
                workflowState={activeSession.workflowState}
                sessionStatus={activeSession.status}
              />
            </div>
          ) : null}
        </header>

        <div className="brand-panel flex min-h-0 flex-1 flex-col overflow-hidden">
          {!sessionId ? (
            <div className="flex h-full items-center justify-center px-10">
              <EmptyStatePanel
                eyebrow="Workspace"
                title="Start a new transfer conversation"
                description="This workspace combines free-text input with structured cards, selectable lists, and guided forms. Create a new chat to begin or reopen a previous session from the left history rail."
              >
                <div className="flex flex-col gap-3 sm:flex-row">
                  <BrandButton onClick={() => createSessionMutation.mutate()} disabled={createSessionMutation.isPending}>
                    {createSessionMutation.isPending ? 'Creating...' : 'New chat'}
                  </BrandButton>
                  {sessions[0] ? (
                    <BrandButton variant="secondary" onClick={() => navigate(`/chat/${sessions[0].sessionId}`)}>
                      Open latest session
                    </BrandButton>
                  ) : null}
                </div>
              </EmptyStatePanel>
            </div>
          ) : sessionQuery.isLoading || messagesQuery.isLoading ? (
            <div className="flex h-full items-center justify-center p-8">
              <BrandLoadingPanel
                title="Loading workspace"
                description="Session details, draft state, and message history are being assembled for this conversation."
              />
            </div>
          ) : (
            <>
              <MessageList
                messages={messages}
                assistantName="Assistant"
                onSubmitUiEvent={(payload) => submitUiEventMutation.mutate(payload)}
                disabled={showAssistantLoading}
                pendingUserText={pendingUserText}
                showAssistantLoading={showAssistantLoading}
              />
              <div className="border-t border-brand-line bg-brand-fog p-5">
                <ChatInputBar
                  disabled={readOnly || !sessionId}
                  busy={busy}
                  onSend={async (messageText) => {
                    await sendMessageMutation.mutateAsync(messageText);
                  }}
                />
              </div>
            </>
          )}
        </div>
      </section>
    </main>
  );
}
