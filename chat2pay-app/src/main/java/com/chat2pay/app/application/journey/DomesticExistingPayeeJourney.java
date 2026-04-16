package com.chat2pay.app.application.journey;

import com.chat2pay.app.api.ApiModels;
import com.chat2pay.app.application.chat.BlockFactory;
import com.chat2pay.app.application.chat.TurnOutcome;
import com.chat2pay.app.common.ApiException;
import com.chat2pay.app.common.UlidFactory;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.ChatSessionStatus;
import com.chat2pay.app.domain.ConversationSession;
import com.chat2pay.app.domain.JourneyType;
import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.Profile;
import com.chat2pay.app.domain.TransferStatus;
import com.chat2pay.app.domain.UiEventType;
import com.chat2pay.app.domain.WorkflowState;
import com.chat2pay.app.integration.downstream.RegisteredPayee;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DomesticExistingPayeeJourney {

    private static final int MAX_AGENT_ITERATIONS = 4;

    private final Chat2PayProperties properties;
    private final UlidFactory ulidFactory;
    private final JourneyAgentPlanner journeyAgentPlanner;
    private final JourneyToolRegistry journeyToolRegistry;
    private final BlockFactory blockFactory;
    private final HeuristicMessageAnalyzer heuristicMessageAnalyzer;

    public DomesticExistingPayeeJourney(
            Chat2PayProperties properties,
            UlidFactory ulidFactory,
            JourneyAgentPlanner journeyAgentPlanner,
            JourneyToolRegistry journeyToolRegistry,
            BlockFactory blockFactory,
            HeuristicMessageAnalyzer heuristicMessageAnalyzer) {
        this.properties = properties;
        this.ulidFactory = ulidFactory;
        this.journeyAgentPlanner = journeyAgentPlanner;
        this.journeyToolRegistry = journeyToolRegistry;
        this.blockFactory = blockFactory;
        this.heuristicMessageAnalyzer = heuristicMessageAnalyzer;
    }

    public TurnOutcome handleText(Profile profile, ConversationSession session, String messageText) {
        JourneyUserSignal signal = new JourneyUserSignal(
                messageText,
                heuristicMessageAnalyzer.analyze(messageText),
                null,
                null,
                Map.of());
        return runAgentLoop(profile, session, signal);
    }

    public TurnOutcome handleUiEvent(Profile profile, ConversationSession session, ApiModels.UiEventRequest request) {
        JourneyUserSignal signal = switch (request.eventType()) {
            case CLICK_ACTION -> new JourneyUserSignal(
                    null,
                    null,
                    firstSelectedItem(request),
                    null,
                    Map.of());
            case SELECT_ITEM -> new JourneyUserSignal(
                    null,
                    null,
                    null,
                    firstSelectedItem(request),
                    Map.of());
            case SUBMIT_FORM -> new JourneyUserSignal(
                    null,
                    null,
                    null,
                    null,
                    request.formValues() == null ? Map.of() : request.formValues());
        };
        return runAgentLoop(profile, session, signal);
    }

    private TurnOutcome runAgentLoop(Profile profile, ConversationSession session, JourneyUserSignal signal) {
        List<JourneyToolResult> toolResults = new ArrayList<>();

        for (int iteration = 0; iteration < MAX_AGENT_ITERATIONS; iteration++) {
            PaymentDraft draft = session.getActiveDraft();
            if (draft == null && shouldCreateDraft(session, signal)) {
                draft = ensureDraft(session);
            }

            JourneyAgentContext context = new JourneyAgentContext(
                    profile,
                    session,
                    draft,
                    signal,
                    availableTools(),
                    List.copyOf(toolResults),
                    iteration);

            JourneyAgentDecision decision = journeyAgentPlanner.plan(context);
            if (decision == null || decision.action() == null) {
                return failOutcome(session, draft, "I couldn't determine the next step for this payment.");
            }

            draft = session.getActiveDraft();
            if (draft == null && decisionRequiresDraft(decision)) {
                draft = ensureDraft(session);
            }

            if (draft != null) {
                applyDraftUpdate(draft, decision.draftUpdate());
            }

            switch (decision.action()) {
                case UNSUPPORTED -> {
                    return unsupportedOutcome(decision.assistantMessage());
                }
                case CANCEL -> {
                    return cancelOutcome(session, decision.assistantMessage());
                }
                case ASK_USER -> {
                    return askUserOutcome(session, draft, decision);
                }
                case SHOW_CONFIRMATION -> {
                    return showConfirmationOutcome(session, draft, decision);
                }
                case COMPLETE -> {
                    JourneyToolResult latestToolResult = toolResults.isEmpty() ? null : toolResults.getLast();
                    return completeOutcome(session, draft, latestToolResult, decision.assistantMessage());
                }
                case FAIL -> {
                    JourneyToolResult latestToolResult = toolResults.isEmpty() ? null : toolResults.getLast();
                    return failOutcome(
                            session,
                            draft,
                            decision.assistantMessage() != null && !decision.assistantMessage().isBlank()
                                    ? decision.assistantMessage()
                                    : latestToolResult != null && latestToolResult.errorMessage() != null
                                            ? latestToolResult.errorMessage()
                                            : "The payment flow could not continue.");
                }
                case CALL_TOOL -> {
                    JourneyToolResult toolResult = executeTool(profile, session, draft, signal, decision);
                    toolResults.add(toolResult);
                }
            }
        }

        return failOutcome(session, session.getActiveDraft(), "I couldn't complete the payment planning loop.");
    }

    private List<JourneyToolDefinition> availableTools() {
        return journeyToolRegistry.availableTools();
    }

    private boolean shouldCreateDraft(ConversationSession session, JourneyUserSignal signal) {
        return session.getActiveDraft() != null
                || signal.hasStructuredFormInput()
                || signal.selectedItemId() != null
                || (signal.analysis() != null
                        && signal.analysis().supportedPaymentIntent()
                        && !signal.analysis().unsupportedRequest());
    }

    private boolean decisionRequiresDraft(JourneyAgentDecision decision) {
        return decision.action() == JourneyAction.ASK_USER
                || decision.action() == JourneyAction.CALL_TOOL
                || decision.action() == JourneyAction.SHOW_CONFIRMATION
                || decision.action() == JourneyAction.COMPLETE
                || decision.action() == JourneyAction.FAIL;
    }

    private JourneyToolResult executeTool(
            Profile profile,
            ConversationSession session,
            PaymentDraft draft,
            JourneyUserSignal signal,
            JourneyAgentDecision decision) {
        if (draft == null) {
            return JourneyToolResult.failure(decision.toolName(), "No active payment draft exists.");
        }
        return journeyToolRegistry.execute(
                decision.toolName(),
                new JourneyToolExecutionContext(profile, session, draft, signal));
    }

    private TurnOutcome askUserOutcome(ConversationSession session, PaymentDraft draft, JourneyAgentDecision decision) {
        if (draft == null) {
            session.setWorkflowState(WorkflowState.IDLE);
            return TurnOutcome.of(List.of(blockFactory.info(
                    "Need more information",
                    defaultMessage(decision.assistantMessage(), "I need more information before I can continue."))));
        }

        if (requiresPayeeSelection(decision) && !draft.getCandidatePayees().isEmpty()) {
            session.setWorkflowState(WorkflowState.RESOLVING_PAYEE);
            draft.setWorkflowState(WorkflowState.RESOLVING_PAYEE);
            return TurnOutcome.of(List.of(
                    blockFactory.text(defaultMessage(
                            decision.assistantMessage(),
                            "I found more than one registered payee. Please choose the intended payee.")),
                    blockFactory.selectableList(
                            "Select payee",
                            draft.getCandidatePayees().values().stream()
                                    .map(payee -> new ApiModels.SelectableItem(
                                            payee.payeeIdIndex(),
                                            payee.name(),
                                            payee.description(),
                                            null,
                                            Map.of("payeeType", payee.payeeType())))
                                    .toList(),
                            Map.of("stage", "PAYEE_SELECTION"))));
        }

        session.setWorkflowState(WorkflowState.COLLECTING_PAYMENT_DETAILS);
        draft.setWorkflowState(WorkflowState.COLLECTING_PAYMENT_DETAILS);
        draft.setStatus(TransferStatus.DRAFT);

        return TurnOutcome.of(List.of(
                blockFactory.text(defaultMessage(
                        decision.assistantMessage(),
                        "I need more information before I can continue.")),
                buildMissingInputForm(decision.requiredInputs(), draft)));
    }

    private TurnOutcome showConfirmationOutcome(ConversationSession session, PaymentDraft draft, JourneyAgentDecision decision) {
        if (draft == null || draft.getPayeeIdIndex() == null || draft.getAmount() == null) {
            return askUserOutcome(session, draft, new JourneyAgentDecision(
                    JourneyAction.ASK_USER,
                    "I still need the registered payee and payment amount before I can show the confirmation.",
                    null,
                    List.of("payeeName", "amount"),
                    JourneyDraftUpdate.empty()));
        }

        session.setJourneyType(JourneyType.DOMESTIC_EXISTING_PAYEE);
        session.setWorkflowState(WorkflowState.AWAITING_USER_CONFIRMATION);
        session.setTitle("Pay " + draft.getPayeeDisplay());
        draft.setWorkflowState(WorkflowState.AWAITING_USER_CONFIRMATION);
        draft.setStatus(TransferStatus.READY_FOR_CONFIRMATION);
        draft.setReviewSummary(buildReviewSummary(draft));

        return TurnOutcome.of(
                List.of(
                        blockFactory.text(defaultMessage(
                                decision.assistantMessage(),
                                "Please review the payment details before confirming.")),
                        blockFactory.summary(
                                "Payment summary",
                                buildReviewFields(draft),
                                Map.of("actions", List.of(
                                        Map.of("id", "CONFIRM_TRANSFER", "label", "Confirm", "tone", "primary"),
                                        Map.of("id", "CANCEL_TRANSFER", "label", "Cancel", "tone", "secondary"))))),
                List.of(
                        new ApiModels.SuggestedActionResponse("CONFIRM_TRANSFER", "Confirm transfer", "CONFIRM_TRANSFER"),
                        new ApiModels.SuggestedActionResponse("CANCEL_TRANSFER", "Cancel transfer", "CANCEL_TRANSFER")));
    }

    private TurnOutcome completeOutcome(
            ConversationSession session,
            PaymentDraft draft,
            JourneyToolResult latestToolResult,
            String assistantMessage) {
        if (draft == null) {
            return failOutcome(session, null, "The payment completed without an active draft, which is invalid.");
        }

        if (latestToolResult == null || !HeuristicJourneyAgentPlanner.TOOL_CONFIRM_DOMESTIC_PAYMENT.equals(latestToolResult.toolName())) {
            return failOutcome(session, draft, "The payment completion step is missing its confirm result.");
        }

        draft.setStatus(TransferStatus.CONFIRMED);
        draft.setWorkflowState(WorkflowState.COMPLETED);
        draft.setTransferReference(String.valueOf(latestToolResult.payload().getOrDefault("transferReference", "UNKNOWN")));
        @SuppressWarnings("unchecked")
        Map<String, Object> downstreamPayload = (Map<String, Object>) latestToolResult.payload()
                .getOrDefault("downstreamPayload", Map.of());
        draft.setDownstreamReferences(new LinkedHashMap<>(downstreamPayload));
        session.setWorkflowState(WorkflowState.COMPLETED);
        session.setStatus(ChatSessionStatus.COMPLETED);

        return TurnOutcome.of(List.of(
                blockFactory.info(
                        "Payment confirmed",
                        defaultMessage(assistantMessage, "The domestic payment was confirmed successfully.")),
                blockFactory.summary(
                        "Payment completed",
                        List.of(
                                new ApiModels.DisplayField("Payee", draft.getPayeeDisplay()),
                                new ApiModels.DisplayField("Amount", formatAmount(draft)),
                                new ApiModels.DisplayField("Transfer reference", draft.getTransferReference()),
                                new ApiModels.DisplayField("Status", draft.getStatus().name())),
                        Map.of())));
    }

    private TurnOutcome cancelOutcome(ConversationSession session, String assistantMessage) {
        PaymentDraft draft = session.getActiveDraft();
        if (draft != null) {
            draft.setStatus(TransferStatus.CANCELLED);
            draft.setWorkflowState(WorkflowState.CANCELLED);
        }
        session.setWorkflowState(WorkflowState.CANCELLED);
        session.setStatus(ChatSessionStatus.CANCELLED);

        return TurnOutcome.of(List.of(blockFactory.info(
                "Payment cancelled",
                defaultMessage(assistantMessage, "The active payment draft has been cancelled."))));
    }

    private TurnOutcome unsupportedOutcome(String assistantMessage) {
        return TurnOutcome.of(List.of(blockFactory.info(
                "Not supported in this POC",
                defaultMessage(assistantMessage, "This POC currently supports domestic payments to existing payees only."))));
    }

    private TurnOutcome failOutcome(ConversationSession session, PaymentDraft draft, String assistantMessage) {
        session.setWorkflowState(WorkflowState.FAILED);
        if (draft != null) {
            draft.setStatus(TransferStatus.FAILED);
            draft.setWorkflowState(WorkflowState.FAILED);
        }
        return TurnOutcome.of(List.of(blockFactory.error(
                "Payment flow failed",
                assistantMessage)));
    }

    private PaymentDraft ensureDraft(ConversationSession session) {
        if (session.getActiveDraft() != null) {
            return session.getActiveDraft();
        }

        PaymentDraft draft = new PaymentDraft(
                ulidFactory.nextUlid(),
                session.getId(),
                JourneyType.DOMESTIC_EXISTING_PAYEE,
                properties.getDemo().getDefaultSourceAccountId(),
                properties.getDemo().getDefaultSourceAccountDisplay(),
                session.getUpdatedAt());
        session.setActiveDraft(draft);
        session.setJourneyType(JourneyType.DOMESTIC_EXISTING_PAYEE);
        return draft;
    }

    private void applyDraftUpdate(PaymentDraft draft, JourneyDraftUpdate update) {
        if (draft == null || update == null || update.isEmpty()) {
            return;
        }

        if (update.payeeNameInput() != null && !update.payeeNameInput().isBlank()) {
            String nextPayeeName = update.payeeNameInput().trim();
            if (!nextPayeeName.equalsIgnoreCase(draft.getPayeeNameInput())) {
                clearResolvedPayee(draft);
            }
            draft.setPayeeNameInput(nextPayeeName);
        }

        if (update.amount() != null) {
            draft.setAmount(update.amount());
        }

        if (update.currency() != null && !update.currency().isBlank()) {
            draft.setCurrency(update.currency().trim().toUpperCase());
        } else if (update.amount() != null && (draft.getCurrency() == null || draft.getCurrency().isBlank())) {
            draft.setCurrency("HKD");
        }

        if (update.note() != null && !update.note().isBlank()) {
            draft.setNote(update.note().trim());
        }

        if (update.selectedPayeeId() != null && !update.selectedPayeeId().isBlank()) {
            RegisteredPayee selectedPayee = draft.getCandidatePayees().get(update.selectedPayeeId());
            if (selectedPayee != null) {
                applyResolvedPayee(draft, selectedPayee);
                draft.clearCandidatePayees();
            }
        }
    }

    private void applyResolvedPayee(PaymentDraft draft, RegisteredPayee payee) {
        draft.setPayeeIdIndex(payee.payeeIdIndex());
        draft.setPayeeType(payee.payeeType());
        draft.setPayeeDisplay(payee.name());
        if (draft.getPayeeNameInput() == null || draft.getPayeeNameInput().isBlank()) {
            draft.setPayeeNameInput(payee.name());
        }
    }

    private void clearResolvedPayee(PaymentDraft draft) {
        draft.setPayeeIdIndex(null);
        draft.setPayeeType(null);
        draft.setPayeeDisplay(null);
        draft.clearCandidatePayees();
    }

    private ApiModels.SimpleFormBlock buildMissingInputForm(List<String> requiredInputs, PaymentDraft draft) {
        List<String> fieldsToAsk = requiredInputs == null || requiredInputs.isEmpty()
                ? List.of("payeeName", "amount", "currency")
                : requiredInputs;

        List<ApiModels.FormField> fields = new ArrayList<>();
        if (fieldsToAsk.contains("payeeName")) {
            fields.add(new ApiModels.FormField(
                    "payee",
                    "Registered payee",
                    "TEXT",
                    true,
                    draft != null && draft.getPayeeNameInput() != null ? draft.getPayeeNameInput() : "BOB",
                    List.of()));
        }
        if (fieldsToAsk.contains("amount")) {
            fields.add(new ApiModels.FormField(
                    "amount",
                    "Amount",
                    "NUMBER",
                    true,
                    draft != null && draft.getAmount() != null ? draft.getAmount().stripTrailingZeros().toPlainString() : "500",
                    List.of()));
        }
        if (fieldsToAsk.contains("currency")) {
            fields.add(new ApiModels.FormField(
                    "currency",
                    "Currency",
                    "SELECT",
                    true,
                    null,
                    List.of(new ApiModels.SelectableItem("HKD", "HKD", null, null, Map.of()))));
        }
        if (draft == null || draft.getNote() == null || draft.getNote().isBlank()) {
            fields.add(new ApiModels.FormField(
                    "note",
                    "Note",
                    "TEXT",
                    false,
                    "Optional note",
                    List.of()));
        }

        return blockFactory.simpleForm(
                "Payment details",
                fields,
                "Continue",
                Map.of("stage", "COLLECT_PAYMENT_DETAILS"));
    }

    private Map<String, Object> buildReviewSummary(PaymentDraft draft) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sourceAccountDisplay", draft.getSourceAccountDisplay());
        summary.put("payeeDisplay", draft.getPayeeDisplay());
        summary.put("amount", draft.getAmount());
        summary.put("currency", draft.getCurrency());
        if (draft.getNote() != null && !draft.getNote().isBlank()) {
            summary.put("note", draft.getNote());
        }
        return summary;
    }

    private List<ApiModels.DisplayField> buildReviewFields(PaymentDraft draft) {
        List<ApiModels.DisplayField> fields = new ArrayList<>();
        fields.add(new ApiModels.DisplayField("Source account", draft.getSourceAccountDisplay()));
        fields.add(new ApiModels.DisplayField("Payee", draft.getPayeeDisplay()));
        fields.add(new ApiModels.DisplayField("Amount", formatAmount(draft)));
        if (draft.getNote() != null && !draft.getNote().isBlank()) {
            fields.add(new ApiModels.DisplayField("Note", draft.getNote()));
        }
        return List.copyOf(fields);
    }

    private boolean requiresPayeeSelection(JourneyAgentDecision decision) {
        return decision.requiredInputs() != null && decision.requiredInputs().contains("selectedPayeeId");
    }

    private String defaultMessage(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private String formatAmount(PaymentDraft draft) {
        BigDecimal amount = draft.getAmount() == null ? BigDecimal.ZERO : draft.getAmount();
        String currency = draft.getCurrency() == null || draft.getCurrency().isBlank() ? "HKD" : draft.getCurrency();
        return amount.stripTrailingZeros().toPlainString() + " " + currency;
    }

    private String firstSelectedItem(ApiModels.UiEventRequest request) {
        if (request.selectedItemIds() == null || request.selectedItemIds().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHAT2PAY-400", "Selected item is required.");
        }
        return request.selectedItemIds().getFirst();
    }
}
