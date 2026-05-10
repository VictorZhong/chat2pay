import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import type {
  ChatMessage,
  ChatSessionDetail,
  ChatSessionSummary,
  ChatTurnResponse,
  ContentBlock,
  SendMessageRequest,
  UiEventRequest,
} from '@/shared/api/contracts';
import { chat2payClient, queryKeys } from '@/shared/api/chat2payClient';
import { USE_MOCK_API } from '@/shared/config/env';
import type { TurnStreamEvent } from '@/shared/api/httpClient';
import { useAuthStore } from '@/features/auth/useAuthStore';
import { useSidebarStore } from '@/features/sidebar/useSidebarStore';
import { Sidebar } from '@/features/sidebar/Sidebar';
import { MessageList } from '@/features/message-renderer/MessageList';
import { ChatInputBar } from '@/features/chat-input/ChatInputBar';
import { EmptyStatePanel } from '@/shared/ui/EmptyStatePanel';
import { BrandButton } from '@/shared/ui/BrandButton';
import { BrandLoadingPanel } from '@/shared/ui/BrandLoadingPanel';
import { ConversationStatusPill } from '@/features/session-history/ConversationStatusPill';
import { SessionTitleEditor } from '@/features/session-history/SessionTitleEditor';
import { DeleteChatDialog } from '@/features/session-history/DeleteChatDialog';
import { INTERACTION_DELAY_MS } from '@/shared/config/env';
import { wait } from '@/shared/lib/time';
import { createId } from '@/shared/lib/id';

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

function cleanText(value: unknown) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length ? trimmed : null;
}

function metadataText(metadata: Record<string, unknown> | null | undefined, key: string) {
  return cleanText(metadata?.[key]);
}

function sourceBlockFromMessages(messages: ChatMessage[], payload: UiEventRequest) {
  const sourceMessage = messages.find((message) => message.messageId === payload.sourceMessageId);
  return sourceMessage?.contentBlocks?.find((block) => block.blockId === payload.sourceBlockId);
}

function joinNonBlank(separator: string, ...values: Array<string | null | undefined>) {
  const parts = values.map((value) => cleanText(value)).filter((value): value is string => Boolean(value));
  return parts.length ? parts.join(separator) : null;
}

function describeSelectableItem(block: Extract<ContentBlock, { type: 'SELECTABLE_LIST' }>, selectedId: string) {
  const item = block.items.find((candidate) => candidate.itemId === selectedId);
  if (!item) return null;
  const metadata =
    item.metadata && typeof item.metadata === 'object' && !Array.isArray(item.metadata)
      ? item.metadata as Record<string, unknown>
      : null;
  const purpose = metadataText(block.metadata as Record<string, unknown> | undefined, 'purpose');

  if (purpose === 'debit-account-selection') {
    const label = joinNonBlank(
      ' • ',
      metadataText(metadata, 'productDescription'),
      metadataText(metadata, 'accountDisplay'),
    ) ?? cleanText(item.label) ?? cleanText(item.description) ?? selectedId;
    return `Chose debit account ${label}`;
  }

  if (purpose === 'payee-selection' || purpose === 'payee-account-selection') {
    const accountLabel =
      metadataText(metadata, 'displayLabel')
      ?? joinNonBlank(' - ', metadataText(metadata, 'accountProductType'), metadataText(metadata, 'accountNumber'))
      ?? cleanText(item.description);
    const label = joinNonBlank(
      ' • ',
      metadataText(metadata, 'payeeNickName') ?? cleanText(item.label),
      accountLabel,
    ) ?? cleanText(item.label) ?? selectedId;
    return `Chose payee ${label}`;
  }

  return `Chose ${cleanText(item.label) ?? selectedId}`;
}

function describeFormEvent(payload: UiEventRequest) {
  const formValues = payload.formValues ?? {};
  const payee = cleanText(formValues.payee);
  const amount = cleanText(formValues.amount);
  const paymentDate = cleanText(formValues.paymentDate);

  if (Object.keys(formValues).length === 1 && paymentDate) return `Chose payment date ${paymentDate}`;
  if (Object.keys(formValues).length === 1 && amount) return `Entered amount ${amount}`;
  if (Object.keys(formValues).length === 1 && payee) return `Entered payee ${payee}`;

  const parts = [
    payee ? `payee ${payee}` : null,
    amount ? `amount ${amount}` : null,
    paymentDate ? `payment date ${paymentDate}` : null,
  ].filter((value): value is string => Boolean(value));
  return parts.length ? `Submitted details: ${parts.join(', ')}` : 'Submitted details';
}

function derivePendingUiEventText(messages: ChatMessage[], payload: UiEventRequest) {
  if (payload.eventType === 'SUBMIT_FORM') {
    return describeFormEvent(payload);
  }

  const sourceBlock = sourceBlockFromMessages(messages, payload);
  const selectedId = payload.selectedItemId ?? payload.actionValue;

  if (!selectedId) {
    return 'Submitted action';
  }

  if (payload.eventType === 'CLICK_ACTION') {
    return actionLabelFromSummaryBlock(sourceBlock, selectedId);
  }

  if (sourceBlock?.type === 'SELECTABLE_LIST') {
    return describeSelectableItem(sourceBlock, selectedId) ?? 'Chose item';
  }

  return 'Submitted action';
}

function optimisticMessage(sessionId: string, text: string, kind: ChatMessage['kind']): ChatMessage {
  return {
    messageId: createId('msg_optimistic'),
    sessionId,
    role: 'USER',
    kind,
    text,
    contentBlocks: null,
    metadata: { optimistic: true },
    createdAt: new Date().toISOString(),
  };
}

function replaceOptimisticUserMessage(messages: ChatMessage[], nextMessage: ChatMessage) {
  const optimisticIndex = messages.findIndex(
    (message) => message.role === 'USER' && Boolean(message.metadata?.optimistic),
  );
  if (optimisticIndex < 0) {
    return messages.some((message) => message.messageId === nextMessage.messageId)
      ? messages
      : [...messages, nextMessage];
  }
  const next = [...messages];
  next[optimisticIndex] = nextMessage;
  return next;
}

function upsertAssistantMessage(messages: ChatMessage[], nextMessage: ChatMessage) {
  const existingIndex = messages.findIndex((message) => message.messageId === nextMessage.messageId);
  if (existingIndex < 0) {
    return [...messages, nextMessage];
  }
  const next = [...messages];
  next[existingIndex] = nextMessage;
  return next;
}

function sessionSummaryFromDetail(session: ChatSessionDetail): ChatSessionSummary {
  return {
    sessionId: session.sessionId,
    title: session.title,
    titleLocked: session.titleLocked,
    status: session.status,
    state: session.state,
    llmProvider: session.llmProvider ?? null,
    lastMessagePreview: session.lastMessagePreview ?? null,
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
  };
}

function summaryActions(block: ContentBlock) {
  if (block.type !== 'SUMMARY_CARD') return [];
  const actions = block.metadata?.actions;
  return Array.isArray(actions)
    ? actions.filter(
        (item): item is { id: string; label: string } =>
          typeof item === 'object' &&
          item !== null &&
          'id' in item &&
          'label' in item &&
          typeof item.id === 'string' &&
          typeof item.label === 'string',
      )
    : [];
}

function pendingInteraction(messages: ChatMessage[]) {
  const latestAssistant = [...messages].reverse().find((message) => message.role === 'ASSISTANT');
  const blocks = latestAssistant?.contentBlocks ?? [];

  for (const block of [...blocks].reverse()) {
    if (block.type === 'SELECTABLE_LIST' && block.items.length > 0) {
      return {
        key: `${latestAssistant?.messageId}:${block.blockId}`,
        reason: 'Select one of the listed payees to continue.',
      };
    }
    if (block.type === 'SUMMARY_CARD' && summaryActions(block).length > 0) {
      return {
        key: `${latestAssistant?.messageId}:${block.blockId}`,
        reason: 'Use the confirmation actions above to continue.',
      };
    }
    if (block.type === 'SIMPLE_FORM') {
      return {
        key: `${latestAssistant?.messageId}:${block.blockId}`,
        reason: 'Submit the form above to continue.',
      };
    }
  }

  return null;
}

function turnErrorMessage(sessionId: string, evt: Extract<TurnStreamEvent, { type: 'turn-error' }>): ChatMessage {
  const messageId = createId('msg_error');
  return {
    messageId,
    sessionId,
    role: 'ASSISTANT',
    kind: 'BLOCKS',
    text: null,
    contentBlocks: [
      {
        blockId: createId('blk_error'),
        type: 'ERROR_CARD',
        title: 'Request failed',
        text: evt.message || 'The request could not be completed.',
      },
    ],
    metadata: {
      errorCode: evt.code,
      ...(typeof evt.processingMs === 'number' ? { processingMs: evt.processingMs } : {}),
    },
    createdAt: new Date().toISOString(),
  };
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : 'The request could not be completed.';
}

function wasDisplayedInChat(error: unknown) {
  return Boolean(error && typeof error === 'object' && 'displayedInChat' in error);
}

function markDisplayedInChat(error: Error) {
  (error as Error & { displayedInChat: true }).displayedInChat = true;
  return error;
}

export function ChatWorkspacePage() {
  const navigate = useNavigate();
  const { sessionId } = useParams();
  const queryClient = useQueryClient();
  const currentUser = useAuthStore((state) => state.currentUser)!;
  const clearCurrentUser = useAuthStore((state) => state.clearCurrentUser);
  const collapsed = useSidebarStore((state) => state.collapsed);
  const setCollapsed = useSidebarStore((state) => state.setCollapsed);
  const pendingQuickActionSessionIdRef = useRef<string | null>(null);
  const [textInputOverrideKey, setTextInputOverrideKey] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<ChatSessionSummary | null>(null);

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

  function refreshCurrentSessionInBackground(nextSessionId: string) {
    void refreshCurrentSession(nextSessionId).catch((error) => {
      console.warn('[chat2pay] session refresh failed', error);
    });
  }

  function setSessionCaches(session: ChatSessionDetail) {
    queryClient.setQueryData(queryKeys.session(currentUser.profileId, session.sessionId), session);
    queryClient.setQueryData<ChatSessionSummary[]>(
      queryKeys.sessions(currentUser.profileId),
      (prev = []) => {
        const summary = sessionSummaryFromDetail(session);
        return prev.some((item) => item.sessionId === session.sessionId)
          ? prev.map((item) => (item.sessionId === session.sessionId ? summary : item))
          : [summary, ...prev];
      },
    );
  }

  function applyTurnResponse(nextSessionId: string, turn: ChatTurnResponse) {
    const messagesKey = queryKeys.messages(currentUser.profileId, nextSessionId);
    queryClient.setQueryData<ChatMessage[]>(messagesKey, (prev = []) => {
      const withUserMessage = turn.userMessage
        ? replaceOptimisticUserMessage(prev, turn.userMessage)
        : prev;
      return upsertAssistantMessage(withUserMessage, turn.assistantMessage);
    });
    setSessionCaches(turn.session);
  }

  const createSessionMutation = useMutation({
    mutationFn: () => chat2payClient.createChatSession(currentUser.profileId),
    onSuccess: async (session) => {
      navigate(`/chat/${session.sessionId}`);
      await refreshCurrentSession(session.sessionId);
    },
  });

  const startChatWithMessageMutation = useMutation({
    mutationFn: async (messageText: string) => {
      const session = await chat2payClient.createChatSession(currentUser.profileId);
      pendingQuickActionSessionIdRef.current = session.sessionId;
      queryClient.setQueryData(queryKeys.session(currentUser.profileId, session.sessionId), session);
      queryClient.setQueryData<ChatSessionSummary[]>(
        queryKeys.sessions(currentUser.profileId),
        (prev = []) =>
          prev.some((item) => item.sessionId === session.sessionId)
            ? prev
            : [sessionSummaryFromDetail(session), ...prev],
      );
      queryClient.setQueryData<ChatMessage[]>(
        queryKeys.messages(currentUser.profileId, session.sessionId),
        [optimisticMessage(session.sessionId, messageText, 'TEXT')],
      );
      navigate(`/chat/${session.sessionId}`);
      const turn = await chat2payClient.sendChatMessage(currentUser.profileId, session.sessionId, {
        messageText,
      });
      applyTurnResponse(session.sessionId, turn);
      return session;
    },
    onSuccess: (session) => {
      pendingQuickActionSessionIdRef.current = null;
      refreshCurrentSessionInBackground(session.sessionId);
    },
    onError: (error) => {
      const targetSessionId = pendingQuickActionSessionIdRef.current;
      if (targetSessionId && !wasDisplayedInChat(error)) {
        appendRequestError(targetSessionId, error);
      }
      pendingQuickActionSessionIdRef.current = null;
    },
  });

  const deleteSessionMutation = useMutation({
    mutationFn: (targetSessionId: string) =>
      chat2payClient.deleteChatSession(currentUser.profileId, targetSessionId),
    onSuccess: async (_void, deletedSessionId) => {
      queryClient.removeQueries({
        queryKey: queryKeys.session(currentUser.profileId, deletedSessionId),
      });
      queryClient.removeQueries({
        queryKey: queryKeys.messages(currentUser.profileId, deletedSessionId),
      });
      await queryClient.invalidateQueries({
        queryKey: queryKeys.sessions(currentUser.profileId),
      });
      if (sessionId === deletedSessionId) {
        navigate('/chat');
      }
      setDeleteTarget(null);
    },
  });

  const renameSessionMutation = useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) =>
      chat2payClient.renameChatSession(currentUser.profileId, id, title),
    onSuccess: async (updated) => {
      queryClient.setQueryData(queryKeys.session(currentUser.profileId, updated.sessionId), updated);
      await queryClient.invalidateQueries({
        queryKey: queryKeys.sessions(currentUser.profileId),
      });
    },
  });

  async function consumeTurnStream(
    nextSessionId: string,
    iter: AsyncGenerator<TurnStreamEvent, void, void>,
  ) {
    const messagesKey = queryKeys.messages(currentUser.profileId, nextSessionId);
    let pendingMessageId: string | null = null;

    for await (const evt of iter) {
      if (evt.type === 'user-message') {
        queryClient.setQueryData<ChatMessage[]>(messagesKey, (prev = []) =>
          replaceOptimisticUserMessage(prev, evt.message),
        );
      } else if (evt.type === 'assistant-message-start') {
        pendingMessageId = evt.messageId;
        queryClient.setQueryData<ChatMessage[]>(messagesKey, (prev = []) => [
          ...prev,
          {
            messageId: evt.messageId,
            sessionId: evt.sessionId,
            role: 'ASSISTANT',
            kind: 'TEXT',
            text: '',
            contentBlocks: null,
            metadata: null,
            createdAt: new Date().toISOString(),
          },
        ]);
      } else if (evt.type === 'assistant-message-delta' && pendingMessageId) {
        queryClient.setQueryData<ChatMessage[]>(messagesKey, (prev = []) =>
          prev.map((m) => {
            if (m.messageId !== pendingMessageId) return m;
            const text = evt.textDelta ? (m.text ?? '') + evt.textDelta : m.text;
            const blocks = evt.block
              ? [...(m.contentBlocks ?? []), evt.block]
              : m.contentBlocks;
            return { ...m, text, contentBlocks: blocks };
          }),
        );
      } else if (evt.type === 'assistant-message-complete') {
        queryClient.setQueryData<ChatMessage[]>(messagesKey, (prev = []) =>
          upsertAssistantMessage(prev, evt.message),
        );
        setSessionCaches(evt.session);
      } else if (evt.type === 'turn-error') {
        const errorMessage = turnErrorMessage(nextSessionId, evt);
        queryClient.setQueryData<ChatMessage[]>(messagesKey, (prev = []) => {
          if (pendingMessageId) {
            return prev.map((m) => (m.messageId === pendingMessageId ? errorMessage : m));
          }
          return [...prev, errorMessage];
        });
        throw markDisplayedInChat(new Error(`${evt.code}: ${evt.message}`));
      }
    }
  }

  function appendRequestError(nextSessionId: string, error: unknown) {
    const messagesKey = queryKeys.messages(currentUser.profileId, nextSessionId);
    queryClient.setQueryData<ChatMessage[]>(messagesKey, (prev = []) => [
      ...prev,
      turnErrorMessage(nextSessionId, {
        type: 'turn-error',
        code: 'REQUEST_FAILED',
        message: errorText(error),
      }),
    ]);
  }

  const sendMessageMutation = useMutation({
    mutationFn: async (messageText: string) => {
      const targetSessionId = sessionId ?? '';
      const payload: SendMessageRequest = { messageText };
      if (USE_MOCK_API) {
        await wait(INTERACTION_DELAY_MS);
        const turn = await chat2payClient.sendChatMessage(currentUser.profileId, targetSessionId, payload);
        applyTurnResponse(targetSessionId, turn);
        return turn;
      }
      const iter = chat2payClient.streamChatMessage(currentUser.profileId, targetSessionId, payload);
      await consumeTurnStream(targetSessionId, iter);
      return null;
    },
    onSuccess: () => {
      if (sessionId) {
        refreshCurrentSessionInBackground(sessionId);
      }
    },
    onError: (error) => {
      if (sessionId && !wasDisplayedInChat(error)) {
        appendRequestError(sessionId, error);
      }
    },
  });

  const submitUiEventMutation = useMutation({
    onMutate: (payload) => {
      if (!sessionId) return;
      queryClient.setQueryData<ChatMessage[]>(queryKeys.messages(currentUser.profileId, sessionId), (prev = []) => [
        ...prev,
        optimisticMessage(sessionId, derivePendingUiEventText(prev, payload), 'UI_EVENT'),
      ]);
    },
    mutationFn: async (payload: UiEventRequest) => {
      const targetSessionId = sessionId ?? '';
      if (USE_MOCK_API) {
        await wait(INTERACTION_DELAY_MS);
        const turn = await chat2payClient.submitUiEvent(currentUser.profileId, targetSessionId, payload);
        applyTurnResponse(targetSessionId, turn);
        return turn;
      }
      const iter = chat2payClient.streamUiEvent(currentUser.profileId, targetSessionId, payload);
      await consumeTurnStream(targetSessionId, iter);
      return null;
    },
    onSuccess: () => {
      if (sessionId) {
        refreshCurrentSessionInBackground(sessionId);
      }
    },
    onError: (error) => {
      if (sessionId && !wasDisplayedInChat(error)) {
        appendRequestError(sessionId, error);
      }
    },
  });

  const sessions = sessionsQuery.data ?? [];
  const activeSession = sessionQuery.data;
  const messages = messagesQuery.data ?? [];
  const requiredInteraction = useMemo(() => pendingInteraction(messages), [messages]);

  useEffect(() => {
    if (requiredInteraction?.key !== textInputOverrideKey) {
      setTextInputOverrideKey(null);
    }
  }, [requiredInteraction?.key, textInputOverrideKey]);

  const busy =
    createSessionMutation.isPending ||
    sendMessageMutation.isPending ||
    submitUiEventMutation.isPending ||
    startChatWithMessageMutation.isPending;
  const completedSession = activeSession?.status === 'COMPLETED';
  const readOnly = !activeSession || completedSession;
  const interactionLocksInput =
    Boolean(requiredInteraction)
    && requiredInteraction?.key !== textInputOverrideKey
    && !readOnly;
  const pendingUserText = sendMessageMutation.isPending
    ? sendMessageMutation.variables
    : undefined;
  const showAssistantLoading =
    sendMessageMutation.isPending || submitUiEventMutation.isPending || startChatWithMessageMutation.isPending;

  return (
    <main className="brand-shell flex min-h-screen flex-col gap-3 bg-transparent p-3 lg:h-screen lg:flex-row">
      <Sidebar
        user={currentUser}
        sessions={sessions}
        selectedSessionId={sessionId}
        collapsed={collapsed}
        onToggleCollapsed={() => setCollapsed(!collapsed)}
        onNewChat={() => createSessionMutation.mutate()}
        onSelectSession={(selectedSessionId) => navigate(`/chat/${selectedSessionId}`)}
        onDeleteSession={(target: ChatSessionSummary) => setDeleteTarget(target)}
        pendingDeleteSessionId={
          deleteSessionMutation.isPending ? (deleteSessionMutation.variables ?? null) : null
        }
        onQuickAction={(messageText) => startChatWithMessageMutation.mutate(messageText)}
        onLogout={() => {
          clearCurrentUser();
          navigate('/');
        }}
      />

      <section className="flex min-w-0 flex-1 flex-col gap-3">
        <header className="brand-panel flex flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:px-5">
          <div className="min-w-0">
            <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-brand-red">Session</p>
            <div className="mt-1">
              {activeSession ? (
                <SessionTitleEditor
                  title={activeSession.title}
                  busy={renameSessionMutation.isPending}
                  onRename={(next) =>
                    renameSessionMutation.mutateAsync({ id: activeSession.sessionId, title: next })
                  }
                />
              ) : (
                <h2 className="text-lg font-semibold text-brand-black">Conversation workspace</h2>
              )}
            </div>
          </div>

          {activeSession ? (
            <div className="flex flex-wrap items-center gap-2">
              <ConversationStatusPill session={activeSession} />
            </div>
          ) : null}
        </header>

        <div className="brand-panel flex min-h-0 flex-1 flex-col overflow-hidden">
          {!sessionId ? (
            <div className="flex h-full items-center justify-center px-10">
              <EmptyStatePanel
                eyebrow="Workspace"
                title="Start a new payment conversation"
                description="Ask about a registered payee, or tell the backend who to pay, how much, and whether it should go today or tomorrow."
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
                onSubmitUiEvent={(payload) => {
                  if (!readOnly && !showAssistantLoading) {
                    submitUiEventMutation.mutate(payload);
                  }
                }}
                disabled={showAssistantLoading || readOnly}
                pendingUserText={pendingUserText}
                showAssistantLoading={showAssistantLoading}
              />
              <ChatInputBar
                disabled={readOnly || !sessionId || interactionLocksInput}
                busy={busy}
                disabledReason={
                  completedSession
                    ? 'This instruction has been submitted successfully. Start a new chat if you need to make another request.'
                    : interactionLocksInput
                      ? requiredInteraction?.reason
                      : undefined
                }
                onEnableTextInput={interactionLocksInput && requiredInteraction
                  ? () => setTextInputOverrideKey(requiredInteraction.key)
                  : undefined}
                onSend={async (messageText) => {
                  await sendMessageMutation.mutateAsync(messageText);
                }}
              />
            </>
          )}
        </div>
      </section>
      <DeleteChatDialog
        session={deleteTarget}
        busy={deleteSessionMutation.isPending}
        onCancel={() => {
          if (!deleteSessionMutation.isPending) {
            setDeleteTarget(null);
          }
        }}
        onConfirm={() => {
          if (deleteTarget) {
            deleteSessionMutation.mutate(deleteTarget.sessionId);
          }
        }}
      />
    </main>
  );
}
