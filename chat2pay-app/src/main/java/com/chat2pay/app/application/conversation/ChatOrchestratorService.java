package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.ChatTurnResponse;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.api.dto.ChatDtos.SendMessageRequest;
import com.chat2pay.app.api.dto.ChatDtos.UiEventRequest;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.api.dto.ContentBlock.DisplayField;
import com.chat2pay.app.api.dto.ContentBlock.SelectableItem;
import com.chat2pay.app.application.capability.CapabilityRegistry;
import com.chat2pay.app.application.conversation.tool.PaymentToolActions;
import com.chat2pay.app.application.conversation.tool.PaymentToolContext;
import com.chat2pay.app.application.conversation.tool.PaymentToolDefinitions;
import com.chat2pay.app.application.conversation.tool.PaymentToolExecution;
import com.chat2pay.app.application.conversation.tool.PaymentToolRegistry;
import com.chat2pay.app.application.conversation.intent.IntentAnalysis;
import com.chat2pay.app.application.conversation.intent.IntentInterpreter;
import com.chat2pay.app.application.conversation.intent.IntentType;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.MessageKind;
import com.chat2pay.app.domain.conversation.MessageRole;
import com.chat2pay.app.integration.llm.LlmCompletionRequest;
import com.chat2pay.app.integration.llm.LlmCompletionRequest.Message;
import com.chat2pay.app.integration.llm.LlmCompletionResponse;
import com.chat2pay.app.integration.llm.LlmCompletionResponse.ToolCall;
import com.chat2pay.app.integration.llm.LlmSelection;
import com.chat2pay.app.integration.llm.LlmRouter;
import com.chat2pay.app.integration.llm.LlmUseCase;
import com.chat2pay.app.persistence.repository.SessionStore;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ChatOrchestratorService implements PaymentToolActions {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestratorService.class);
    private static final int MAX_TOOL_LOOP_ITERATIONS = 4;
    private static final Pattern PAYMENT_DOMAIN_TERMS = Pattern.compile(
            "\\b(pay|payment|transfer|payee|payees|registered|domestic|swift|wire|international|overseas|cross[- ]?border|confirm|cancel|hkd|amount)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMON_CHAT = Pattern.compile(
            "\\b(hi|hello|hey|good morning|good afternoon|good evening|what can you do|how can you help|help me|capabilities|what do you support)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OBVIOUSLY_UNRELATED = Pattern.compile(
            "\\b(weather|news|sports?|joke|story|poem|recipe|movie|music|translate|code|programming|homework|math|stock|crypto|restaurant|travel|flight|hotel|email|account balance|balance|loan|credit card|mortgage|investment|insurance)\\b",
            Pattern.CASE_INSENSITIVE);

    private final SessionStore sessions;
    private final IntentInterpreter intentInterpreter;
    private final LlmRouter llmRouter;
    private final Chat2PayProperties properties;
    private final ObjectMapper mapper;
    private final SessionTitleSuggester titleSuggester;
    private final ChatBlockFactory blocks;
    private final ConversationStateMachine stateMachine;
    private final PaymentToolRegistry paymentTools;
    private final PaymentPolicyGuard policyGuard;
    private final CapabilityRegistry capabilityRegistry;
    private final DomesticPaymentJourneyService domesticJourney;

    public ChatOrchestratorService(SessionStore sessions,
                                   IntentInterpreter intentInterpreter,
                                   LlmRouter llmRouter,
                                   Chat2PayProperties properties,
                                   ObjectMapper mapper,
                                   SessionTitleSuggester titleSuggester,
                                   ChatBlockFactory blocks,
                                   ConversationStateMachine stateMachine,
                                   PaymentToolRegistry paymentTools,
                                   PaymentPolicyGuard policyGuard,
                                   CapabilityRegistry capabilityRegistry,
                                   DomesticPaymentJourneyService domesticJourney) {
        this.sessions = sessions;
        this.intentInterpreter = intentInterpreter;
        this.llmRouter = llmRouter;
        this.properties = properties;
        this.mapper = mapper;
        this.titleSuggester = titleSuggester;
        this.blocks = blocks;
        this.stateMachine = stateMachine;
        this.paymentTools = paymentTools;
        this.policyGuard = policyGuard;
        this.capabilityRegistry = capabilityRegistry;
        this.domesticJourney = domesticJourney;
    }

    public ChatMessage welcomeMessage(String sessionId) {
        return assistantMessage(sessionId, List.of(
                textBlock("Domestic payments only",
                        "Ask about a registered payee, or tell me who to pay, how much, and whether it should go today or tomorrow.")
        ));
    }

    public ChatTurnResponse handleUserMessage(String profileId, String sessionId, SendMessageRequest request) {
        long startedNanos = System.nanoTime();
        String text = request.messageText().trim();
        CompletedTurn turn = sessions.applyTurn(profileId, sessionId, record -> {
            ChatMessage userMessage = userTextMessage(sessionId, text);
            record.messages().add(userMessage);

            ChatMessage assistant = withProcessingMetadata(handleTextTurn(record, text), startedNanos);
            record.messages().add(assistant);
            stateMachine.touch(record);
            log.debug("Chat text turn processed: profileId={} sessionId={} userMessageId={} assistantMessageId={} processingMs={}",
                    profileId, sessionId, userMessage.messageId(), assistant.messageId(), processingMs(assistant));

            return new CompletedTurn(record.session(), userMessage, assistant, record.messageSnapshot());
        });

        ChatSessionDetail session = maybeSuggestTitle(profileId, turn.session(), turn.messages());
        return new ChatTurnResponse(session, turn.userMessage(), turn.assistantMessage(),
                session.activeDraft(), Instant.now());
    }

    public ChatTurnResponse handleUiEvent(String profileId, String sessionId, UiEventRequest request) {
        long startedNanos = System.nanoTime();
        CompletedTurn turn = sessions.applyTurn(profileId, sessionId, record -> {
            String userText = describeEvent(record, request);
            ChatMessage userMessage = new ChatMessage(
                    newMessageId(), sessionId, MessageRole.USER, MessageKind.UI_EVENT,
                    userText, null, null, Instant.now()
            );
            record.messages().add(userMessage);

            ChatMessage assistant = withProcessingMetadata(handleUiTurn(record, request), startedNanos);
            record.messages().add(assistant);
            stateMachine.touch(record);
            log.debug("Chat UI turn processed: profileId={} sessionId={} eventType={} userMessageId={} assistantMessageId={} processingMs={}",
                    profileId, sessionId, request.eventType(), userMessage.messageId(), assistant.messageId(),
                    processingMs(assistant));

            return new CompletedTurn(record.session(), userMessage, assistant, record.messageSnapshot());
        });

        ChatSessionDetail session = maybeSuggestTitle(profileId, turn.session(), turn.messages());
        return new ChatTurnResponse(session, turn.userMessage(), turn.assistantMessage(),
                session.activeDraft(), Instant.now());
    }

    private ChatSessionDetail maybeSuggestTitle(String profileId, ChatSessionDetail s, List<ChatMessage> messages) {
        titleSuggester.suggestIfEligible(
                profileId,
                s.sessionId(),
                s.title(),
                s.titleLocked(),
                messages);
        // Pull the latest title from the DB so the response reflects any change.
        try {
            return sessions.get(profileId, s.sessionId()).session();
        } catch (RuntimeException ignored) {
            // If reload fails (e.g. session was just deleted), keep the snapshot we have.
            return s;
        }
    }

    // ---- text turn ------------------------------------------------------------

    private ChatMessage handleTextTurn(SessionRecord record, String text) {
        if (isObviouslyUnrelated(text)) return outOfScopeGuidance(record);
        Optional<ChatMessage> loopResponse = handleWithLlmToolLoop(record, text);
        if (loopResponse.isPresent()) return loopResponse.get();
        return handleTextTurnDeterministic(record, text);
    }

    private ChatMessage handleTextTurnDeterministic(SessionRecord record, String text) {
        ConversationState state = record.session().state();
        IntentAnalysis intent = intentInterpreter.analyze(record.session(), text, record.profileId());

        if (state == ConversationState.AWAITING_CONFIRMATION) {
            if (intent.intent() == IntentType.CONFIRM_PAYMENT) return domesticJourney.executePayment(record);
            if (intent.intent() == IntentType.CANCEL_PAYMENT) return domesticJourney.cancelPayment(record);
        }

        if (state == ConversationState.AWAITING_DEBIT_ACCOUNT_SELECTION
                && record.session().activeDraft() != null
                && intent.intent() != IntentType.DOMESTIC_PAYMENT
                && intent.intent() != IntentType.ACCOUNT_LOOKUP) {
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("Select a debit account",
                            "Please choose one of the available debit accounts before I continue.")
            ));
        }

        if (state == ConversationState.AWAITING_PAYEE_SELECTION
                && record.session().activeDraft() != null
                && intent.intent() != IntentType.DOMESTIC_PAYMENT
                && intent.intent() != IntentType.PAYEE_LOOKUP) {
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("Select a payee",
                            "Please choose one of the registered payees from the selection list before I continue.")
            ));
        }

        if (intent.intent() == IntentType.CROSS_BORDER_PAYMENT) {
            return domesticJourney.unsupportedCrossBorderPayment(record);
        }

        if (intent.intent() == IntentType.ACCOUNT_LOOKUP) {
            return domesticJourney.handleDebitAccountLookup(record);
        }
        if (intent.intent() == IntentType.DOMESTIC_PAYMENT) return domesticJourney.continueDomesticPayment(record, intent);
        if (intent.intent() == IntentType.PAYEE_LOOKUP) {
            return domesticJourney.handlePayeeLookup(record, intent.payeeQuery());
        }

        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Try a supported request",
                        "Ask me to list your accounts, find a registered payee, or tell me who to pay, how much, and whether it should go now or later.")
        ));
    }

    private Optional<ChatMessage> handleWithLlmToolLoop(SessionRecord record, String text) {
        if (!properties.useLlmIntent()) return Optional.empty();
        Optional<LlmSelection> selection = llmRouter.select(LlmUseCase.CHAT);
        if (selection.isEmpty()) return Optional.empty();
        try {
            return runLlmToolLoop(selection.get(), record, text);
        } catch (RuntimeException ex) {
            log.warn("LLM tool loop failed; falling back to deterministic orchestrator: {}", ex.getMessage());
            log.debug("LLM tool loop failure details", ex);
            return Optional.empty();
        }
    }

    private Optional<ChatMessage> runLlmToolLoop(LlmSelection selection, SessionRecord record, String latestUserText) {
        List<Message> messages = buildToolLoopMessages(record);
        List<ContentBlock> pendingBlocks = List.of();

        for (int iteration = 0; iteration < MAX_TOOL_LOOP_ITERATIONS; iteration++) {
            LlmCompletionRequest request = new LlmCompletionRequest(
                    messages,
                    properties.intentMaxTokens(),
                    properties.intentTemperature(),
                    capabilityRegistry.llmToolDefinitions(),
                    "auto"
            ).withModel(selection.modelOverride());
            LlmCompletionResponse response = selection.provider().complete(request);
            List<ToolCall> toolCalls = normalizeToolCalls(response.toolCalls());
            log.debug("LLM tool loop response: sessionId={} iteration={} provider={} content={} toolCalls={}",
                    record.session().sessionId(), iteration, response.provider(), response.content(), toolCalls);

            if (toolCalls.isEmpty()) {
                String content = trim(response.content());
                if (pendingBlocks.isEmpty() && shouldFallbackWhenNoTool(record, latestUserText)) {
                    return Optional.empty();
                }
                if (content == null && pendingBlocks.isEmpty()) return Optional.empty();
                return Optional.of(renderLoopAnswer(record.session().sessionId(), content, pendingBlocks));
            }

            messages.add(Message.assistantToolCalls(toolCalls.stream()
                    .map(tc -> new LlmCompletionRequest.ToolCall(tc.id(), tc.name(), tc.arguments()))
                    .toList()));

            ToolCall selected = toolCalls.get(0);
            log.debug("LLM tool loop executing tool: sessionId={} iteration={} toolName={} toolCallId={} arguments={}",
                    record.session().sessionId(), iteration, selected.name(), selected.id(), selected.arguments());
            PaymentToolExecution execution = executeLlmToolCall(record, selected, latestUserText);
            log.debug("LLM tool loop tool result: sessionId={} iteration={} toolName={} toolCallId={} result={} terminalMessage={} renderBlocks={}",
                    record.session().sessionId(), iteration, execution.toolName(), execution.toolCallId(),
                    toJson(execution.result()), execution.terminalMessage() != null, execution.renderBlocks());
            messages.add(Message.toolResult(
                    execution.toolCallId(),
                    execution.toolName(),
                    serializeToolResult(execution.result())
            ));

            if (execution.terminalMessage() != null) {
                return Optional.of(execution.terminalMessage());
            }
            pendingBlocks = execution.renderBlocks();
        }

        if (!pendingBlocks.isEmpty()) {
            return Optional.of(assistantMessage(record.session().sessionId(), pendingBlocks));
        }
        return Optional.empty();
    }

    private boolean shouldFallbackWhenNoTool(SessionRecord record, String latestUserText) {
        // Only override the LLM with deterministic handling when the session is mid-flow and the
        // user explicitly confirms or cancels — those state transitions must always succeed even
        // if the LLM forgets to emit a tool call. For everything else, trust the LLM's text
        // response so capability/meta questions ("may I send to a new payee?", "what is the
        // payment rail behind?") don't get force-routed into the domestic-payment journey just
        // because the message contains words like "send" or "payment".
        if (record.session().state() == ConversationState.AWAITING_CONFIRMATION) {
            return policyGuard.hasExplicitConfirmation(latestUserText)
                    || policyGuard.isCancellation(latestUserText);
        }
        return false;
    }

    private boolean isObviouslyUnrelated(String text) {
        return OBVIOUSLY_UNRELATED.matcher(text).find()
                && !PAYMENT_DOMAIN_TERMS.matcher(text).find()
                && !COMMON_CHAT.matcher(text).find();
    }

    private ChatMessage outOfScopeGuidance(SessionRecord record) {
        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Chat2Pay payments only",
                        "I can help with your debit accounts, registered domestic payees, and domestic payments in this POC. Ask me to list your accounts, find a registered payee, or tell me who to pay, how much, and whether it should go now or later.")
        ));
    }

    private List<Message> buildToolLoopMessages(SessionRecord record) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message(LlmCompletionRequest.Role.SYSTEM,
                PaymentToolDefinitions.paymentAssistantPrompt()
                        + "\nCurrent date: " + LocalDate.now()
                        + "\nCurrent session state: " + record.session().state()
                        + "\nActive draft: " + draftSummary(record.session().activeDraft())));

        List<ChatMessage> history = record.messages();
        int start = Math.max(0, history.size() - 12);
        for (int i = start; i < history.size(); i++) {
            ChatMessage message = history.get(i);
            String content = trim(messageContent(message));
            if (content == null) continue;
            LlmCompletionRequest.Role role = message.role() == MessageRole.ASSISTANT
                    ? LlmCompletionRequest.Role.ASSISTANT
                    : LlmCompletionRequest.Role.USER;
            messages.add(new Message(role, content));
        }
        return messages;
    }

    private ChatMessage renderLoopAnswer(String sessionId, String content, List<ContentBlock> pendingBlocks) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (content != null) {
            blocks.add(textBlock(pendingBlocks.isEmpty() ? "Chat2Pay" : "Registered payees", content));
        }
        if (content != null && !pendingBlocks.isEmpty() && pendingBlocks.get(0) instanceof ContentBlock.TextBlock) {
            blocks.addAll(pendingBlocks.subList(1, pendingBlocks.size()));
        } else {
            blocks.addAll(pendingBlocks);
        }
        return assistantMessage(sessionId, blocks);
    }

    private PaymentToolExecution executeLlmToolCall(SessionRecord record, ToolCall toolCall, String latestUserText) {
        Map<String, Object> args = parseToolArguments(toolCall.arguments());
        return paymentTools.execute(new PaymentToolContext(record, toolCall, args, latestUserText, this));
    }

    @Override
    public PaymentToolExecution executeListDebitAccountsTool(PaymentToolContext context) {
        return domesticJourney.executeListDebitAccountsTool(context);
    }

    @Override
    public PaymentToolExecution executeRegisteredPayeesTool(PaymentToolContext context) {
        String query = IntentInterpreter.sanitizePayeeQuery(
                firstString(context.args(), "name_query", "payeeQuery", "payee_name", "payeeName"));
        return domesticJourney.executeRegisteredPayeesTool(context, query);
    }

    @Override
    public PaymentToolExecution executePrepareDomesticPaymentTool(PaymentToolContext context) {
        SessionRecord record = context.record();
        ToolCall toolCall = context.toolCall();
        Map<String, Object> args = context.args();
        ChatMessage response = domesticJourney.continueDomesticPayment(record, new IntentAnalysis(
                IntentType.DOMESTIC_PAYMENT,
                IntentAnalysis.toolNameFor(IntentType.DOMESTIC_PAYMENT),
                IntentInterpreter.sanitizePayeeQuery(
                        firstString(args, "payeeQuery", "name_query", "payee_name", "payeeName")),
                numberArg(args, "amount"),
                dateArg(args, "paymentDate", "payment_date"),
                "LLM_TOOL_LOOP"
        ));
        return terminalTool(toolCall, draftToolResult(record, "prepare_domestic_payment"), response);
    }

    @Override
    public PaymentToolExecution executeConfirmDomesticPaymentTool(PaymentToolContext context) {
        SessionRecord record = context.record();
        ToolCall toolCall = context.toolCall();
        PaymentPolicyGuard.PolicyDecision decision = policyGuard.canConfirmDomesticPayment(
                record, context.latestUserText());
        if (!decision.allowed()) {
            return terminalTool(toolCall,
                    Map.of("ok", false, "error", decision.code()),
                    domesticJourney.explicitConfirmationRequired(record, decision));
        }
        ChatMessage response = domesticJourney.executePayment(record);
        return terminalTool(toolCall, draftToolResult(record, "confirm_domestic_payment"), response);
    }

    @Override
    public PaymentToolExecution executeCancelPaymentTool(PaymentToolContext context) {
        return terminalTool(context.toolCall(), Map.of("ok", true, "action", "cancel_payment"),
                domesticJourney.cancelPayment(context.record()));
    }

    @Override
    public PaymentToolExecution executeUnsupportedCrossBorderPaymentTool(PaymentToolContext context) {
        ToolCall toolCall = context.toolCall();
        PaymentPolicyGuard.PolicyDecision decision = policyGuard.crossBorderUnavailableInV1();
        return terminalTool(toolCall,
                Map.of("ok", false, "error", decision.code()),
                domesticJourney.unsupportedCrossBorderPayment(context.record()));
    }

    @Override
    public PaymentToolExecution executeUnknownPaymentTool(PaymentToolContext context) {
        ToolCall toolCall = context.toolCall();
        return terminalTool(toolCall,
                Map.of("ok", false, "error", "Unsupported tool: " + toolCall.name()),
                assistantMessage(context.record().session().sessionId(), List.of(
                        infoBlock("Try a supported request",
                                "Chat2Pay currently supports debit-account listing, registered domestic payee lookup, and domestic payments only.")
                )));
    }

    private PaymentToolExecution terminalTool(ToolCall toolCall, Map<String, Object> result, ChatMessage message) {
        return new PaymentToolExecution(toolCall.id(), toolCall.name(), result, message, List.of());
    }

    private Map<String, Object> draftToolResult(SessionRecord record, String toolName) {
        PaymentDraft draft = record.session().activeDraft();
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("tool_name", toolName);
        result.put("session_state", record.session().state().name());
        if (draft != null) {
            result.put("draft_status", draft.status().name());
            result.put("payee", draft.selectedPayee() == null
                    ? draft.payeeQueryText()
                    : draft.selectedPayee().name());
            if (draft.selectedDebitAccount() != null) {
                result.put("debit_account", draft.selectedDebitAccount().displayLabel());
            }
            if (draft.amount() != null) result.put("amount", draft.amount());
            if (draft.paymentDate() != null) result.put("payment_date", draft.paymentDate().toString());
            if (draft.downstreamReference() != null) result.put("downstream_reference", draft.downstreamReference());
        }
        return result;
    }

    // ---- UI turn --------------------------------------------------------------

    private ChatMessage handleUiTurn(SessionRecord record, UiEventRequest request) {
        return switch (request.eventType()) {
            case SELECT_ITEM -> handleSelectItem(record, request);
            case CLICK_ACTION -> handleClickAction(record, request);
            case SUBMIT_FORM -> handleSubmitForm(record, request);
        };
    }

    private ChatMessage handleSelectItem(SessionRecord record, UiEventRequest request) {
        if (record.session().state() == ConversationState.AWAITING_DEBIT_ACCOUNT_SELECTION) {
            return domesticJourney.selectDebitAccount(record, request.selectedItemId());
        }
        return domesticJourney.selectPayee(record, request.selectedItemId());
    }

    private ChatMessage handleClickAction(SessionRecord record, UiEventRequest request) {
        String action = request.actionValue();
        if ("CONFIRM_PAYMENT".equals(action)) {
            applyInlineDraftEdits(record, request.formValues());
            return domesticJourney.executePayment(record);
        }
        if ("CANCEL_PAYMENT".equals(action)) return domesticJourney.cancelPayment(record);
        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Nothing changed", "That action is not available in the current conversation state.")));
    }

    /**
     * Apply inline edits from interactive controls embedded in a summary/confirmation card
     * (currently just the payment-date picker) before executing the action. Silently ignores
     * unparseable or unchanged values so the confirm path stays robust.
     */
    private void applyInlineDraftEdits(SessionRecord record, Map<String, String> formValues) {
        if (formValues == null || formValues.isEmpty()) return;
        com.chat2pay.app.api.dto.ChatDtos.PaymentDraft draft = record.session().activeDraft();
        if (draft == null) return;
        String pickedDate = formValues.get("paymentDate");
        if (pickedDate == null || pickedDate.isBlank()) return;
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(pickedDate.trim());
        } catch (RuntimeException ex) {
            return;
        }
        if (parsed.equals(draft.paymentDate())) return;
        domesticJourney.updatePaymentDate(record, parsed);
    }

    private ChatMessage handleSubmitForm(SessionRecord record, UiEventRequest request) {
        return domesticJourney.submitDetails(record, request.formValues());
    }

    // ---- helpers --------------------------------------------------------------

    private record CompletedTurn(
            ChatSessionDetail session,
            ChatMessage userMessage,
            ChatMessage assistantMessage,
            List<ChatMessage> messages
    ) {}

    private String draftSummary(PaymentDraft draft) {
        if (draft == null) return "none";
        return "payeeQuery=%s, selectedPayee=%s, selectedDebitAccount=%s, amount=%s, currency=%s, paymentDate=%s, status=%s"
                .formatted(
                        draft.payeeQueryText(),
                        draft.selectedPayee() == null ? null : draft.selectedPayee().name(),
                        draft.selectedDebitAccount() == null ? null : draft.selectedDebitAccount().displayLabel(),
                        draft.amount(),
                        draft.currency(),
                        draft.paymentDate(),
                        draft.status()
                );
    }

    private List<ToolCall> normalizeToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return List.of();
        List<ToolCall> normalized = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall toolCall = toolCalls.get(i);
            String name = trim(toolCall.name());
            if (name == null) continue;
            String id = trim(toolCall.id());
            normalized.add(new ToolCall(
                    id == null ? "call_" + (i + 1) : id,
                    name,
                    trim(toolCall.arguments()) == null ? "{}" : toolCall.arguments()
            ));
        }
        if (normalized.size() > 1) {
            log.warn("LLM returned multiple tool calls; executing first and dropping additional tools: sessionToolNames={}",
                    normalized.subList(1, normalized.size()).stream().map(ToolCall::name).toList());
        }
        return normalized;
    }

    private Map<String, Object> parseToolArguments(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("LLM tool arguments were not valid JSON", ex);
        }
    }

    private String serializeToolResult(Map<String, Object> result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception ex) {
            return result.toString();
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private String messageContent(ChatMessage message) {
        if (message.text() != null && !message.text().isBlank()) return message.text();
        if (message.contentBlocks() == null || message.contentBlocks().isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (ContentBlock block : message.contentBlocks()) {
            String text = blockContent(block);
            if (text != null) parts.add(text);
        }
        return String.join("\n", parts);
    }

    private String blockContent(ContentBlock block) {
        if (block instanceof ContentBlock.TextBlock t) return t.title() + ": " + t.text();
        if (block instanceof ContentBlock.InfoCardBlock i) return i.title() + ": " + i.text();
        if (block instanceof ContentBlock.ErrorCardBlock e) return e.title() + ": " + e.text();
        if (block instanceof ContentBlock.SummaryCardBlock s) {
            StringBuilder b = new StringBuilder(s.title());
            for (DisplayField f : s.fields()) {
                b.append("\n").append(f.label()).append(": ").append(f.value());
            }
            return b.toString();
        }
        if (block instanceof ContentBlock.SelectableListBlock s) {
            StringBuilder b = new StringBuilder(s.title());
            for (SelectableItem item : s.items()) {
                b.append("\n").append(item.label());
                if (item.description() != null && !item.description().isBlank()) {
                    b.append(": ").append(item.description());
                }
            }
            return b.toString();
        }
        return null;
    }

    private String firstString(Map<String, Object> args, String... keys) {
        for (String key : keys) {
            String value = trim(args.get(key) == null ? null : args.get(key).toString());
            if (value != null) return value;
        }
        return null;
    }

    private BigDecimal numberArg(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (raw instanceof BigDecimal bd) return bd.signum() > 0 ? bd : null;
        if (raw instanceof Number n) {
            BigDecimal value = new BigDecimal(n.toString());
            return value.signum() > 0 ? value : null;
        }
        String value = trim(raw == null ? null : raw.toString());
        if (value == null) return null;
        try {
            BigDecimal parsed = new BigDecimal(value.replace(",", "").replaceAll("(?i)hkd", "").trim());
            return parsed.signum() > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDate dateArg(Map<String, Object> args, String... keys) {
        String value = firstString(args, keys);
        if (value == null) return null;
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("tomorrow") || normalized.contains("later")) return LocalDate.now().plusDays(1);
        if (normalized.contains("today") || normalized.contains("now")) return LocalDate.now();
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // ---- block builders -------------------------------------------------------

    private ContentBlock.TextBlock textBlock(String title, String text) {
        return blocks.textBlock(title, text);
    }

    private ContentBlock.InfoCardBlock infoBlock(String title, String text) {
        return blocks.infoBlock(title, text);
    }

    // ---- message constructors -------------------------------------------------

    private ChatMessage assistantMessage(String sessionId, List<ContentBlock> blocks) {
        return this.blocks.assistantMessage(sessionId, blocks);
    }

    private ChatMessage withProcessingMetadata(ChatMessage message, long startedNanos) {
        long elapsedMs = Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
        Map<String, Object> metadata = new HashMap<>();
        if (message.metadata() != null) metadata.putAll(message.metadata());
        metadata.put("processingMs", elapsedMs);
        metadata.put("processingCompletedAt", Instant.now().toString());
        return new ChatMessage(
                message.messageId(),
                message.sessionId(),
                message.role(),
                message.kind(),
                message.text(),
                message.contentBlocks(),
                metadata,
                message.createdAt()
        );
    }

    private static Object processingMs(ChatMessage message) {
        return message.metadata() == null ? null : message.metadata().get("processingMs");
    }

    private ChatMessage userTextMessage(String sessionId, String text) {
        return blocks.userTextMessage(sessionId, text);
    }

    private String describeEvent(SessionRecord record, UiEventRequest request) {
        return switch (request.eventType()) {
            case CLICK_ACTION -> describeActionEvent(record, request);
            case SELECT_ITEM -> describeSelectionEvent(record, request);
            case SUBMIT_FORM -> describeFormEvent(request);
        };
    }

    private String describeActionEvent(SessionRecord record, UiEventRequest request) {
        ContentBlock sourceBlock = sourceBlock(record, request).orElse(null);
        String actionValue = trim(request.actionValue());
        if (sourceBlock instanceof ContentBlock.SummaryCardBlock summary
                && summary.metadata() != null
                && summary.metadata().get("actions") instanceof List<?> actions
                && actionValue != null) {
            for (Object action : actions) {
                if (!(action instanceof Map<?, ?> map)) continue;
                String id = trim(map.get("id") == null ? null : map.get("id").toString());
                String label = trim(map.get("label") == null ? null : map.get("label").toString());
                if (actionValue.equals(id) && label != null) return label;
            }
        }
        return "CONFIRM_PAYMENT".equals(actionValue) ? "Confirm payment"
                : "CANCEL_PAYMENT".equals(actionValue) ? "Cancel payment"
                : "Clicked action";
    }

    private String describeSelectionEvent(SessionRecord record, UiEventRequest request) {
        String selectedId = trim(request.selectedItemId());
        if (selectedId == null) return "Chose item";
        ContentBlock sourceBlock = sourceBlock(record, request).orElse(null);
        if (sourceBlock instanceof ContentBlock.SelectableListBlock list) {
            ContentBlock.SelectableItem item = list.items().stream()
                    .filter(candidate -> selectedId.equals(candidate.itemId()))
                    .findFirst()
                    .orElse(null);
            if (item != null) {
                String purpose = metadataText(list.metadata(), "purpose");
                String itemText = describeSelectableItem(item, purpose);
                if ("debit-account-selection".equals(purpose)) return "Chose debit account " + itemText;
                if ("payee-selection".equals(purpose) || "payee-account-selection".equals(purpose)) {
                    return "Chose payee " + itemText;
                }
                return "Chose " + itemText;
            }
        }
        return "Chose item " + selectedId;
    }

    private String describeFormEvent(UiEventRequest request) {
        Map<String, String> formValues = request.formValues();
        if (formValues == null || formValues.isEmpty()) return "Submitted details";

        String paymentDate = trim(formValues.get("paymentDate"));
        String payee = trim(formValues.get("payee"));
        String amount = trim(formValues.get("amount"));
        if (formValues.size() == 1 && paymentDate != null) return "Chose payment date " + paymentDate;
        if (formValues.size() == 1 && amount != null) return "Entered amount " + amount;
        if (formValues.size() == 1 && payee != null) return "Entered payee " + payee;

        List<String> parts = new ArrayList<>();
        if (payee != null) parts.add("payee " + payee);
        if (amount != null) parts.add("amount " + amount);
        if (paymentDate != null) parts.add("payment date " + paymentDate);
        return parts.isEmpty() ? "Submitted details" : "Submitted details: " + String.join(", ", parts);
    }

    private Optional<ContentBlock> sourceBlock(SessionRecord record, UiEventRequest request) {
        String sourceMessageId = trim(request.sourceMessageId());
        String sourceBlockId = trim(request.sourceBlockId());
        if (sourceMessageId == null || sourceBlockId == null) return Optional.empty();
        return record.messages().stream()
                .filter(message -> sourceMessageId.equals(message.messageId()))
                .findFirst()
                .flatMap(message -> message.contentBlocks() == null
                        ? Optional.empty()
                        : message.contentBlocks().stream()
                        .filter(block -> sourceBlockId.equals(block.blockId()))
                        .findFirst());
    }

    private String describeSelectableItem(ContentBlock.SelectableItem item, String purpose) {
        Map<String, Object> metadata = item.metadata();
        if ("debit-account-selection".equals(purpose)) {
            return firstNonBlank(
                    metadataText(metadata, "displayLabel"),
                    joinNonBlank(" • ", metadataText(metadata, "productDescription"), metadataText(metadata, "accountDisplay")),
                    item.label(),
                    item.description(),
                    item.itemId());
        }
        if ("payee-selection".equals(purpose) || "payee-account-selection".equals(purpose)) {
            String payeeName = firstNonBlank(
                    metadataText(metadata, "payeeNickName"),
                    metadataText(metadata, "payeeContactFullName"),
                    item.label());
            String accountDisplay = firstNonBlank(
                    metadataText(metadata, "displayLabel"),
                    joinNonBlank(" - ", metadataText(metadata, "accountProductType"), metadataText(metadata, "accountNumber")),
                    item.description());
            return joinNonBlank(" • ", payeeName, accountDisplay);
        }
        return firstNonBlank(item.label(), item.description(), item.itemId());
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) return null;
        Object raw = metadata.get(key);
        return trim(raw == null ? null : raw.toString());
    }

    private String joinNonBlank(String separator, String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            String trimmed = trim(value);
            if (trimmed != null) parts.add(trimmed);
        }
        return parts.isEmpty() ? null : String.join(separator, parts);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trim(value);
            if (trimmed != null) return trimmed;
        }
        return null;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String newMessageId() {
        return ChatBlockFactory.newMessageId();
    }

}
