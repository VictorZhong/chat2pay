package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.ChatTurnResponse;
import com.chat2pay.app.api.dto.ChatDtos.ErrorSummary;
import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.api.dto.ChatDtos.SendMessageRequest;
import com.chat2pay.app.api.dto.ChatDtos.UiEventRequest;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.api.dto.ContentBlock.DisplayField;
import com.chat2pay.app.api.dto.ContentBlock.SelectableItem;
import com.chat2pay.app.application.conversation.tool.PaymentToolDefinitions;
import com.chat2pay.app.application.conversation.intent.IntentAnalysis;
import com.chat2pay.app.application.conversation.intent.IntentInterpreter;
import com.chat2pay.app.application.conversation.intent.IntentType;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.MessageKind;
import com.chat2pay.app.domain.conversation.MessageRole;
import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.domain.payment.PaymentType;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient.DomesticPaymentRequest;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient.PaymentConfirmationResult;
import com.chat2pay.app.integration.llm.LlmCompletionRequest;
import com.chat2pay.app.integration.llm.LlmCompletionRequest.Message;
import com.chat2pay.app.integration.llm.LlmCompletionResponse;
import com.chat2pay.app.integration.llm.LlmCompletionResponse.ToolCall;
import com.chat2pay.app.integration.llm.LlmProvider;
import com.chat2pay.app.integration.llm.LlmRouter;
import com.chat2pay.app.persistence.repository.PayeeStore;
import com.chat2pay.app.persistence.repository.PayeeStore.RegisteredPayee;
import com.chat2pay.app.persistence.repository.SessionStore;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ChatOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestratorService.class);
    private static final String DEFAULT_CURRENCY = "HKD";
    private static final int MAX_TOOL_LOOP_ITERATIONS = 4;
    private static final Pattern EXPLICIT_CONFIRMATION = Pattern.compile(
            "\\b(confirm|confirmed|yes|okay|ok|go ahead|proceed|send it|approve)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYMENT_RELATED = Pattern.compile(
            "\\b(pay|payment|send|transfer|payee|registered|lookup|find|show|list|confirm|cancel|swift|wire|international|overseas)\\b",
            Pattern.CASE_INSENSITIVE);

    private final SessionStore sessions;
    private final PayeeStore payees;
    private final IntentInterpreter intentInterpreter;
    private final DomesticPaymentClient domesticPayments;
    private final LlmRouter llmRouter;
    private final Chat2PayProperties properties;
    private final ObjectMapper mapper;

    public ChatOrchestratorService(SessionStore sessions,
                                   PayeeStore payees,
                                   IntentInterpreter intentInterpreter,
                                   DomesticPaymentClient domesticPayments,
                                   LlmRouter llmRouter,
                                   Chat2PayProperties properties,
                                   ObjectMapper mapper) {
        this.sessions = sessions;
        this.payees = payees;
        this.intentInterpreter = intentInterpreter;
        this.domesticPayments = domesticPayments;
        this.llmRouter = llmRouter;
        this.properties = properties;
        this.mapper = mapper;
    }

    public ChatMessage welcomeMessage(String sessionId) {
        return assistantMessage(sessionId, List.of(
                textBlock("Domestic payments only",
                        "Ask about a registered payee, or tell me who to pay, how much, and whether it should go today or tomorrow.")
        ));
    }

    public ChatTurnResponse handleUserMessage(String profileId, String sessionId, SendMessageRequest request) {
        SessionRecord record = sessions.get(profileId, sessionId);
        String text = request.messageText().trim();
        ChatMessage userMessage = userTextMessage(sessionId, text);
        record.messages().add(userMessage);

        ChatMessage assistant = handleTextTurn(record, text);
        record.messages().add(assistant);
        touchPreview(record, assistant);

        return new ChatTurnResponse(
                record.session(), userMessage, assistant,
                record.session().activeDraft(), Instant.now()
        );
    }

    public ChatTurnResponse handleUiEvent(String profileId, String sessionId, UiEventRequest request) {
        SessionRecord record = sessions.get(profileId, sessionId);
        String userText = describeEvent(request);
        ChatMessage userMessage = new ChatMessage(
                newMessageId(), sessionId, MessageRole.USER, MessageKind.UI_EVENT,
                userText, null, null, Instant.now()
        );
        record.messages().add(userMessage);

        ChatMessage assistant = handleUiTurn(record, request);
        record.messages().add(assistant);
        touchPreview(record, assistant);

        return new ChatTurnResponse(
                record.session(), userMessage, assistant,
                record.session().activeDraft(), Instant.now()
        );
    }

    // ---- text turn ------------------------------------------------------------

    private ChatMessage handleTextTurn(SessionRecord record, String text) {
        Optional<ChatMessage> loopResponse = handleWithLlmToolLoop(record, text);
        if (loopResponse.isPresent()) return loopResponse.get();
        return handleTextTurnDeterministic(record, text);
    }

    private ChatMessage handleTextTurnDeterministic(SessionRecord record, String text) {
        ConversationState state = record.session().state();
        IntentAnalysis intent = intentInterpreter.analyze(record.session(), text);

        if (state == ConversationState.AWAITING_CONFIRMATION) {
            if (intent.intent() == IntentType.CONFIRM_PAYMENT) return executePayment(record);
            if (intent.intent() == IntentType.CANCEL_PAYMENT) return cancelPayment(record);
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

        if (intent.intent() == IntentType.INTERNATIONAL_PAYMENT) {
            transition(record, ConversationState.IDLE, ChatSessionStatus.ACTIVE);
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("Not supported in V1",
                            "This POC currently supports registered payee lookup and domestic payment to a registered payee only.")
            ));
        }

        if (intent.intent() == IntentType.DOMESTIC_PAYMENT) return continueDomesticPayment(record, intent);
        if (intent.intent() == IntentType.PAYEE_LOOKUP) {
            return handlePayeeLookup(record, intent);
        }

        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Try a supported request",
                        "Ask me to find a registered payee, or tell me who to pay, how much, and whether it should go now or later.")
        ));
    }

    private Optional<ChatMessage> handleWithLlmToolLoop(SessionRecord record, String text) {
        if (!properties.useLlmIntent()) return Optional.empty();
        Optional<LlmProvider> provider = llmRouter.currentIfAvailable();
        if (provider.isEmpty()) return Optional.empty();
        try {
            return runLlmToolLoop(provider.get(), record, text);
        } catch (RuntimeException ex) {
            log.warn("LLM tool loop failed; falling back to deterministic orchestrator: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ChatMessage> runLlmToolLoop(LlmProvider provider, SessionRecord record, String latestUserText) {
        List<Message> messages = buildToolLoopMessages(record);
        List<ContentBlock> pendingBlocks = List.of();

        for (int iteration = 0; iteration < MAX_TOOL_LOOP_ITERATIONS; iteration++) {
            LlmCompletionRequest request = new LlmCompletionRequest(
                    messages,
                    properties.intentMaxTokens(),
                    properties.intentTemperature(),
                    PaymentToolDefinitions.all(),
                    "auto"
            );
            LlmCompletionResponse response = provider.complete(request);
            List<ToolCall> toolCalls = normalizeToolCalls(response.toolCalls());

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

            ToolExecution execution = executeLlmToolCall(record, toolCalls.get(0), latestUserText);
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
        if (record.session().state() == ConversationState.AWAITING_CONFIRMATION
                && EXPLICIT_CONFIRMATION.matcher(latestUserText).find()) {
            return true;
        }
        return PAYMENT_RELATED.matcher(latestUserText).find();
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

    private ToolExecution executeLlmToolCall(SessionRecord record, ToolCall toolCall, String latestUserText) {
        Map<String, Object> args = parseToolArguments(toolCall.arguments());
        return switch (toolCall.name()) {
            case "get_registered_payees" -> executePayeeLookupTool(record, toolCall,
                    firstString(args, "name_query", "payeeQuery", "payee_name", "payeeName"));
            case "prepare_domestic_payment" -> executePrepareDomesticPaymentTool(record, toolCall, args);
            case "confirm_domestic_payment" -> executeConfirmPaymentTool(record, toolCall, latestUserText);
            case "cancel_payment" -> terminalTool(toolCall, Map.of("ok", true, "action", "cancel_payment"),
                    cancelPayment(record));
            case "unsupported_international_payment" -> executeUnsupportedInternationalTool(record, toolCall);
            default -> terminalTool(toolCall,
                    Map.of("ok", false, "error", "Unsupported tool: " + toolCall.name()),
                    assistantMessage(record.session().sessionId(), List.of(
                            infoBlock("Try a supported request",
                                    "Chat2Pay currently supports registered domestic payee lookup and domestic payments only.")
                    )));
        };
    }

    private ToolExecution executePayeeLookupTool(SessionRecord record, ToolCall toolCall, String query) {
        PayeeLookupView view = buildPayeeLookupView(record, query);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        if (query != null) result.put("query", query);
        result.put("match_count", view.matches().size());
        result.put("payees", view.matches().stream()
                .map(p -> Map.of(
                        "name", p.summary().name(),
                        "bank_code", p.summary().bankCode(),
                        "bank_name", p.summary().bankName(),
                        "account_number", p.summary().accountNumber(),
                        "display_label", p.summary().displayLabel()
                ))
                .toList());
        return new ToolExecution(toolCall.id(), toolCall.name(), result, null, view.blocks());
    }

    private ToolExecution executePrepareDomesticPaymentTool(SessionRecord record,
                                                            ToolCall toolCall,
                                                            Map<String, Object> args) {
        ChatMessage response = continueDomesticPayment(record, new IntentAnalysis(
                IntentType.DOMESTIC_PAYMENT,
                IntentAnalysis.toolNameFor(IntentType.DOMESTIC_PAYMENT),
                firstString(args, "payeeQuery", "name_query", "payee_name", "payeeName"),
                numberArg(args, "amount"),
                dateArg(args, "paymentDate", "payment_date"),
                "LLM_TOOL_LOOP"
        ));
        return terminalTool(toolCall, draftToolResult(record, "prepare_domestic_payment"), response);
    }

    private ToolExecution executeConfirmPaymentTool(SessionRecord record, ToolCall toolCall, String latestUserText) {
        if (record.session().state() != ConversationState.AWAITING_CONFIRMATION
                || !EXPLICIT_CONFIRMATION.matcher(latestUserText).find()) {
            PaymentDraft draft = record.session().activeDraft();
            List<ContentBlock> blocks = new ArrayList<>();
            blocks.add(infoBlock("Explicit confirmation required",
                    "Please review the domestic payment summary and explicitly confirm before I submit it."));
            if (draft != null) blocks.add(summaryBlock("Current draft", draftFields(draft), null));
            return terminalTool(toolCall,
                    Map.of("ok", false, "error", "explicit_confirmation_required"),
                    assistantMessage(record.session().sessionId(), blocks));
        }
        ChatMessage response = executePayment(record);
        return terminalTool(toolCall, draftToolResult(record, "confirm_domestic_payment"), response);
    }

    private ToolExecution executeUnsupportedInternationalTool(SessionRecord record, ToolCall toolCall) {
        transition(record, ConversationState.IDLE, ChatSessionStatus.ACTIVE);
        ChatMessage response = assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Not supported in V1",
                        "This POC currently supports registered payee lookup and domestic payment to a registered payee only.")
        ));
        return terminalTool(toolCall,
                Map.of("ok", false, "error", "international_payment_not_supported"),
                response);
    }

    private ToolExecution terminalTool(ToolCall toolCall, Map<String, Object> result, ChatMessage message) {
        return new ToolExecution(toolCall.id(), toolCall.name(), result, message, List.of());
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
            if (draft.amount() != null) result.put("amount", draft.amount());
            if (draft.paymentDate() != null) result.put("payment_date", draft.paymentDate().toString());
            if (draft.downstreamReference() != null) result.put("downstream_reference", draft.downstreamReference());
        }
        return result;
    }

    private ChatMessage handlePayeeLookup(SessionRecord record, IntentAnalysis intent) {
        PayeeLookupView view = buildPayeeLookupView(record, intent.payeeQuery());
        return assistantMessage(record.session().sessionId(), view.blocks());
    }

    private PayeeLookupView buildPayeeLookupView(SessionRecord record, String query) {
        List<RegisteredPayee> matches = query != null ? payees.findByQuery(query) : payees.all();

        transition(record, ConversationState.IDLE, ChatSessionStatus.ACTIVE);
        if (query != null) setTitle(record, "Find " + titleCase(query));
        else setTitle(record, "Registered payees");

        if (matches.isEmpty()) {
            return new PayeeLookupView(matches, List.of(
                    infoBlock("No registered payees found",
                            query != null
                                    ? "I could not find a registered payee matching \"" + query + "\"."
                                    : "There are no registered payees available in this profile.")
            ));
        }

        List<DisplayField> fields = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            PayeeSummary p = matches.get(i).summary();
            fields.add(new DisplayField(
                    matches.size() == 1 ? p.name() : "Match " + (i + 1),
                    p.name() + " • " + p.bankName() + " • " + p.displayLabel()));
        }

        String headline = query != null
                ? "I found " + matches.size() + " registered payee" + (matches.size() == 1 ? "" : "s")
                  + " matching \"" + query + "\"."
                : "I found " + matches.size() + " registered payees for this profile.";

        return new PayeeLookupView(matches, List.of(
                textBlock("Registered payees", headline),
                summaryBlock(matches.size() == 1 ? "Registered payee" : "Registered payee results", fields, null)
        ));
    }

    private ChatMessage continueDomesticPayment(SessionRecord record, IntentAnalysis intent) {
        PaymentDraft draft = ensureDraft(record);

        String payeeQuery = intent.payeeQuery();
        Double amount = intent.amount();
        LocalDate date = intent.paymentDate();

        draft = updateDraft(draft,
                payeeQuery != null ? payeeQuery : draft.payeeQueryText(),
                payeeQuery != null ? null : draft.selectedPayee(),
                amount != null ? amount : draft.amount(),
                date != null ? date : draft.paymentDate(),
                draft.status(), null);
        record.setSession(withDraft(record.session(), draft));

        if (draft.payeeQueryText() == null) return askForMissingDetails(record, draft);

        List<RegisteredPayee> matches = payees.findByQuery(draft.payeeQueryText());
        if (matches.isEmpty()) {
            transition(record, ConversationState.COLLECTING_DETAILS, ChatSessionStatus.ACTIVE);
            titleFromDraft(record, draft);
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("Registered payee not found",
                            "I could not find a registered payee matching \"" + draft.payeeQueryText()
                                    + "\". Please try another payee name."),
                    summaryBlock("Current draft", draftFields(draft), null)
            ));
        }

        if (matches.size() > 1) {
            transition(record, ConversationState.AWAITING_PAYEE_SELECTION, ChatSessionStatus.ACTIVE);
            titleFromDraft(record, draft);
            List<SelectableItem> items = matches.stream().map(p ->
                    new SelectableItem(p.summary().payeeId(), p.summary().name(),
                            p.summary().bankName() + " • " + p.summary().displayLabel(),
                            null, null)).toList();
            return assistantMessage(record.session().sessionId(), List.of(
                    textBlock("Choose payee",
                            "I found more than one registered payee for \"" + draft.payeeQueryText()
                                    + "\". Please choose the correct one."),
                    new ContentBlock.SelectableListBlock(newBlockId("list"),
                            "Registered payee matches", items, Map.of("purpose", "payee-selection"))
            ));
        }

        draft = updateDraft(draft, draft.payeeQueryText(), matches.get(0).summary(),
                draft.amount(), draft.paymentDate(), draft.status(), null);
        record.setSession(withDraft(record.session(), draft));

        if (draft.amount() == null || draft.paymentDate() == null) return askForMissingDetails(record, draft);
        return prepareConfirmation(record, draft);
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
        String selectedId = request.selectedItemId();
        if (selectedId == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Invalid selection", "No item was selected.")));
        }
        var payee = payees.findById(selectedId).orElse(null);
        PaymentDraft draft = record.session().activeDraft();
        if (payee == null || draft == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Selection expired", "The selected payee is no longer available.")));
        }

        draft = updateDraft(draft, draft.payeeQueryText(), payee.summary(),
                draft.amount(), draft.paymentDate(), draft.status(), null);
        record.setSession(withDraft(record.session(), draft));
        if (draft.amount() == null || draft.paymentDate() == null) return askForMissingDetails(record, draft);
        return prepareConfirmation(record, draft);
    }

    private ChatMessage handleClickAction(SessionRecord record, UiEventRequest request) {
        String action = request.actionValue();
        if ("CONFIRM_PAYMENT".equals(action)) return executePayment(record);
        if ("CANCEL_PAYMENT".equals(action)) return cancelPayment(record);
        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Nothing changed", "That action is not available in the current conversation state.")));
    }

    private ChatMessage handleSubmitForm(SessionRecord record, UiEventRequest request) {
        PaymentDraft draft = record.session().activeDraft();
        if (draft == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("No active draft", "Start a payment request before submitting details.")));
        }
        Map<String, String> values = request.formValues() == null ? Map.of() : request.formValues();
        String payeeQuery = trim(values.get("payee"));
        String amountText = trim(values.get("amount"));
        String paymentDate = trim(values.get("paymentDate"));

        Double amount = draft.amount();
        if (amountText != null) {
            try {
                double parsed = Double.parseDouble(amountText);
                if (parsed > 0) amount = parsed;
            } catch (NumberFormatException ignored) { }
        }
        LocalDate date = draft.paymentDate();
        if (paymentDate != null) {
            try { date = LocalDate.parse(paymentDate); } catch (Exception ignored) { }
        }

        draft = updateDraft(draft,
                payeeQuery != null ? payeeQuery : draft.payeeQueryText(),
                payeeQuery != null ? null : draft.selectedPayee(),
                amount, date, draft.status(), null);
        record.setSession(withDraft(record.session(), draft));
        return continueDomesticPayment(record, new IntentAnalysis(
                IntentType.DOMESTIC_PAYMENT,
                IntentAnalysis.toolNameFor(IntentType.DOMESTIC_PAYMENT),
                null,
                null,
                null,
                "UI_EVENT"
        ));
    }

    // ---- state transitions ----------------------------------------------------

    private ChatMessage askForMissingDetails(SessionRecord record, PaymentDraft draft) {
        draft = updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(),
                draft.amount(), draft.paymentDate(), PaymentDraftStatus.DRAFT, null);
        record.setSession(withDraft(record.session(), draft));
        transition(record, ConversationState.COLLECTING_DETAILS, ChatSessionStatus.ACTIVE);
        titleFromDraft(record, draft);

        List<String> missing = new ArrayList<>();
        if (draft.payeeQueryText() == null && draft.selectedPayee() == null) missing.add("payee");
        if (draft.amount() == null) missing.add("amount");
        if (draft.paymentDate() == null) missing.add("payment date");

        String prompt = missing.size() == 1
                ? "I still need the " + missing.get(0) + " before I can prepare the domestic payment."
                : "I still need these details before I can prepare the domestic payment: "
                        + String.join(", ", missing) + ".";

        return assistantMessage(record.session().sessionId(), List.of(
                textBlock("Need more details", prompt),
                summaryBlock("Current draft", draftFields(draft), null)
        ));
    }

    private ChatMessage prepareConfirmation(SessionRecord record, PaymentDraft draft) {
        draft = updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                draft.paymentDate(), PaymentDraftStatus.AWAITING_CONFIRMATION, null);
        record.setSession(withDraft(record.session(), draft));
        transition(record, ConversationState.AWAITING_CONFIRMATION, ChatSessionStatus.ACTIVE);
        titleFromDraft(record, draft);

        Map<String, Object> actions = Map.of("actions", List.of(
                Map.of("id", "CONFIRM_PAYMENT", "label", "Confirm payment"),
                Map.of("id", "CANCEL_PAYMENT", "label", "Cancel", "tone", "secondary")
        ));

        return assistantMessage(record.session().sessionId(), List.of(
                textBlock("Awaiting confirmation",
                        "Please confirm the payee, amount, and payment date before I submit the domestic payment."),
                summaryBlock("Domestic payment summary", draftFields(draft), actions)
        ));
    }

    private ChatMessage executePayment(SessionRecord record) {
        PaymentDraft draft = record.session().activeDraft();
        if (draft == null || draft.selectedPayee() == null
                || draft.amount() == null || draft.paymentDate() == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Unable to execute",
                            "The payment draft is incomplete. Please provide the missing details first.")));
        }

        draft = updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                draft.paymentDate(), PaymentDraftStatus.EXECUTING, draft.downstreamReference());
        record.setSession(withDraft(record.session(), draft));
        transition(record, ConversationState.EXECUTING, ChatSessionStatus.ACTIVE);

        PaymentConfirmationResult result;
        try {
            result = domesticPayments.confirm(new DomesticPaymentRequest(
                    draft.selectedPayee().payeeId(),
                    draft.selectedPayee().name(),
                    draft.amount(),
                    draft.paymentDate()
            ));
            if (!result.ok()) {
                throw new IllegalStateException(result.message() == null
                        ? "Domestic payment confirmation was rejected." : result.message());
            }
        } catch (RuntimeException ex) {
            Map<String, Object> context = withContext(draft.context(), Map.of(
                    "downstreamError", ex.getMessage() == null ? "Unknown downstream error" : ex.getMessage()
            ));
            draft = updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                    draft.paymentDate(), PaymentDraftStatus.FAILED, draft.downstreamReference(),
                    new ErrorSummary("DOWNSTREAM_PAYMENT_FAILED", ex.getMessage()), context);
            record.setSession(withDraft(record.session(), draft));
            transition(record, ConversationState.FAILED, ChatSessionStatus.FAILED);
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Payment failed",
                            "The domestic payment could not be submitted: " + ex.getMessage()),
                    summaryBlock("Failed payment", draftFields(draft), null)
            ));
        }

        String reference = result.reference() == null || result.reference().isBlank()
                ? "DOM-" + LocalDate.now().toString().replace("-", "") + "-"
                    + draft.draftId().substring(Math.max(0, draft.draftId().length() - 4))
                : result.reference();
        Map<String, Object> context = withContext(draft.context(), Map.of(
                "downstreamStatusCode", result.statusCode(),
                "downstreamResponse", result.response() == null ? Map.of() : result.response()
        ));
        draft = updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                draft.paymentDate(), PaymentDraftStatus.CONFIRMED, reference, null, context);
        record.setSession(withDraft(record.session(), draft));
        transition(record, ConversationState.COMPLETED, ChatSessionStatus.COMPLETED);

        List<DisplayField> fields = new ArrayList<>(draftFields(draft));
        fields.add(new DisplayField("Reference", reference));

        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Payment submitted",
                        "The domestic payment to " + draft.selectedPayee().name()
                                + " was submitted successfully."),
                summaryBlock("Completed payment", fields, null)
        ));
    }

    private ChatMessage cancelPayment(SessionRecord record) {
        PaymentDraft draft = record.session().activeDraft();
        if (draft == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("No active draft", "There is no active domestic payment draft to cancel.")));
        }
        draft = updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                draft.paymentDate(), PaymentDraftStatus.CANCELLED, draft.downstreamReference());
        record.setSession(withDraft(record.session(), draft));
        transition(record, ConversationState.CANCELLED, ChatSessionStatus.CANCELLED);
        String who = draft.selectedPayee() != null ? draft.selectedPayee().name()
                : draft.payeeQueryText() != null ? draft.payeeQueryText() : "the selected payee";
        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Payment cancelled",
                        "The domestic payment draft for " + who + " was cancelled."),
                summaryBlock("Cancelled draft", draftFields(draft), null)
        ));
    }

    // ---- helpers --------------------------------------------------------------

    private record ToolExecution(
            String toolCallId,
            String toolName,
            Map<String, Object> result,
            ChatMessage terminalMessage,
            List<ContentBlock> renderBlocks
    ) {}

    private record PayeeLookupView(List<RegisteredPayee> matches, List<ContentBlock> blocks) {}

    private String draftSummary(PaymentDraft draft) {
        if (draft == null) return "none";
        return "payeeQuery=%s, selectedPayee=%s, amount=%s, currency=%s, paymentDate=%s, status=%s"
                .formatted(
                        draft.payeeQueryText(),
                        draft.selectedPayee() == null ? null : draft.selectedPayee().name(),
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

    private Double numberArg(Map<String, Object> args, String key) {
        Object raw = args.get(key);
        if (raw instanceof Number n) {
            double value = n.doubleValue();
            return value > 0 ? value : null;
        }
        String value = trim(raw == null ? null : raw.toString());
        if (value == null) return null;
        try {
            double parsed = Double.parseDouble(value.replace(",", "").replaceAll("(?i)hkd", "").trim());
            return parsed > 0 ? parsed : null;
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

    private PaymentDraft ensureDraft(SessionRecord record) {
        PaymentDraft existing = record.session().activeDraft();
        if (existing != null) return existing;
        PaymentDraft draft = new PaymentDraft(
                "draft_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                record.session().sessionId(), PaymentType.DOMESTIC_PAYMENT, PaymentDraftStatus.DRAFT,
                null, null, null, DEFAULT_CURRENCY, null, null, null, null, Instant.now()
        );
        record.setSession(withDraft(record.session(), draft));
        return draft;
    }

    private PaymentDraft updateDraft(PaymentDraft d, String payeeQuery, PayeeSummary selected,
                                     Double amount, LocalDate date, PaymentDraftStatus status,
                                     String reference) {
        return updateDraft(d, payeeQuery, selected, amount, date, status, reference, null);
    }

    private PaymentDraft updateDraft(PaymentDraft d, String payeeQuery, PayeeSummary selected,
                                     Double amount, LocalDate date, PaymentDraftStatus status,
                                     String reference, ErrorSummary lastError) {
        return updateDraft(d, payeeQuery, selected, amount, date, status, reference, lastError, d.context());
    }

    private PaymentDraft updateDraft(PaymentDraft d, String payeeQuery, PayeeSummary selected,
                                     Double amount, LocalDate date, PaymentDraftStatus status,
                                     String reference, ErrorSummary lastError,
                                     Map<String, Object> context) {
        return new PaymentDraft(d.draftId(), d.sessionId(), d.paymentType(), status,
                payeeQuery, selected, amount, d.currency() != null ? d.currency() : DEFAULT_CURRENCY,
                date, reference, lastError, context, Instant.now());
    }

    private Map<String, Object> withContext(Map<String, Object> existing, Map<String, Object> updates) {
        Map<String, Object> next = new HashMap<>();
        if (existing != null) next.putAll(existing);
        next.putAll(updates);
        return next;
    }

    private ChatSessionDetail withDraft(ChatSessionDetail s, PaymentDraft draft) {
        return new ChatSessionDetail(s.sessionId(), s.title(), s.status(), s.state(),
                s.llmProvider(), draft, s.createdAt(), Instant.now());
    }

    private void transition(SessionRecord record, ConversationState state, ChatSessionStatus status) {
        ChatSessionDetail s = record.session();
        record.setSession(new ChatSessionDetail(s.sessionId(), s.title(), status, state,
                s.llmProvider(), s.activeDraft(), s.createdAt(), Instant.now()));
    }

    private void setTitle(SessionRecord record, String title) {
        ChatSessionDetail s = record.session();
        String trimmed = title.length() > 120 ? title.substring(0, 120) : title;
        record.setSession(new ChatSessionDetail(s.sessionId(), trimmed, s.status(), s.state(),
                s.llmProvider(), s.activeDraft(), s.createdAt(), Instant.now()));
    }

    private void titleFromDraft(SessionRecord record, PaymentDraft draft) {
        if (draft.selectedPayee() != null) setTitle(record, "Pay " + draft.selectedPayee().name());
        else if (draft.payeeQueryText() != null) setTitle(record, "Pay " + titleCase(draft.payeeQueryText()));
    }

    private void touchPreview(SessionRecord record, ChatMessage assistant) {
        // ChatSessionDetail has no preview field (matches contract); previews are derived at list time.
        // Bump updatedAt so listings reflect the latest assistant turn.
        ChatSessionDetail s = record.session();
        record.setSession(new ChatSessionDetail(s.sessionId(), s.title(), s.status(), s.state(),
                s.llmProvider(), s.activeDraft(), s.createdAt(), Instant.now()));
        @SuppressWarnings("unused") String unused = previewOf(assistant);
    }

    private String previewOf(ChatMessage message) {
        if (message.text() != null && !message.text().isBlank()) {
            return message.text().length() > 140 ? message.text().substring(0, 140) : message.text();
        }
        if (message.contentBlocks() != null && !message.contentBlocks().isEmpty()) {
            ContentBlock first = message.contentBlocks().get(0);
            if (first instanceof ContentBlock.TextBlock t) return t.text();
            if (first instanceof ContentBlock.InfoCardBlock i) return i.text();
            if (first instanceof ContentBlock.ErrorCardBlock e) return e.text();
            if (first instanceof ContentBlock.SummaryCardBlock s) return s.title();
            if (first instanceof ContentBlock.SelectableListBlock s) return s.title();
        }
        return "";
    }

    private String titleCase(String s) {
        String[] parts = s.split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b.toString();
    }

    // ---- block builders -------------------------------------------------------

    private ContentBlock.TextBlock textBlock(String title, String text) {
        return new ContentBlock.TextBlock(newBlockId("text"), title, text, null);
    }

    private ContentBlock.InfoCardBlock infoBlock(String title, String text) {
        return new ContentBlock.InfoCardBlock(newBlockId("info"), title, text, null);
    }

    private ContentBlock.ErrorCardBlock errorBlock(String title, String text) {
        return new ContentBlock.ErrorCardBlock(newBlockId("error"), title, text, null);
    }

    private ContentBlock.SummaryCardBlock summaryBlock(String title, List<DisplayField> fields,
                                                       Map<String, Object> metadata) {
        return new ContentBlock.SummaryCardBlock(newBlockId("summary"), title, fields, metadata);
    }

    private List<DisplayField> draftFields(PaymentDraft d) {
        String amount = d.amount() != null
                ? String.format(Locale.ROOT, "%s %,.2f", d.currency() != null ? d.currency() : DEFAULT_CURRENCY, d.amount())
                : "Pending";
        return List.of(
                new DisplayField("Payee", d.selectedPayee() != null ? d.selectedPayee().name()
                        : d.payeeQueryText() != null ? d.payeeQueryText() : "Pending"),
                new DisplayField("Account", d.selectedPayee() != null ? d.selectedPayee().displayLabel() : "Pending"),
                new DisplayField("Bank", d.selectedPayee() != null ? d.selectedPayee().bankName() : "Pending"),
                new DisplayField("Amount", amount),
                new DisplayField("Payment date", d.paymentDate() != null ? d.paymentDate().toString() : "Pending")
        );
    }

    // ---- message constructors -------------------------------------------------

    private ChatMessage assistantMessage(String sessionId, List<ContentBlock> blocks) {
        return new ChatMessage(newMessageId(), sessionId, MessageRole.ASSISTANT,
                blocks.isEmpty() ? MessageKind.TEXT : MessageKind.BLOCKS,
                null, blocks.isEmpty() ? null : blocks, null, Instant.now());
    }

    private ChatMessage userTextMessage(String sessionId, String text) {
        return new ChatMessage(newMessageId(), sessionId, MessageRole.USER, MessageKind.TEXT,
                text, null, null, Instant.now());
    }

    private String describeEvent(UiEventRequest request) {
        return switch (request.eventType()) {
            case CLICK_ACTION -> "CONFIRM_PAYMENT".equals(request.actionValue()) ? "Confirm payment"
                    : "CANCEL_PAYMENT".equals(request.actionValue()) ? "Cancel payment"
                    : "Clicked action";
            case SELECT_ITEM -> "Selected " + (request.selectedItemId() != null ? request.selectedItemId() : "item");
            case SUBMIT_FORM -> "Submitted details";
        };
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String newMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private static String newBlockId(String kind) {
        return "blk_" + kind + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
