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
import com.chat2pay.app.domain.MessageAnalysis;
import com.chat2pay.app.domain.PayeeMatchResult;
import com.chat2pay.app.domain.PaymentConfirmationResult;
import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.Profile;
import com.chat2pay.app.domain.RegisteredPayee;
import com.chat2pay.app.domain.TransferStatus;
import com.chat2pay.app.domain.UiEventType;
import com.chat2pay.app.domain.WorkflowState;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DomesticExistingPayeeJourney {

    private final Chat2PayProperties properties;
    private final UlidFactory ulidFactory;
    private final LlmProvider llmProvider;
    private final RegisteredPayeeDirectoryClient payeeDirectoryClient;
    private final DomesticPaymentClient domesticPaymentClient;
    private final PayeeMatcher payeeMatcher;
    private final BlockFactory blockFactory;

    public DomesticExistingPayeeJourney(
            Chat2PayProperties properties,
            UlidFactory ulidFactory,
            LlmProvider llmProvider,
            RegisteredPayeeDirectoryClient payeeDirectoryClient,
            DomesticPaymentClient domesticPaymentClient,
            PayeeMatcher payeeMatcher,
            BlockFactory blockFactory) {
        this.properties = properties;
        this.ulidFactory = ulidFactory;
        this.llmProvider = llmProvider;
        this.payeeDirectoryClient = payeeDirectoryClient;
        this.domesticPaymentClient = domesticPaymentClient;
        this.payeeMatcher = payeeMatcher;
        this.blockFactory = blockFactory;
    }

    public TurnOutcome handleText(Profile profile, ConversationSession session, String messageText) {
        MessageAnalysis analysis = llmProvider.analyze(profile, messageText);
        if (analysis.unsupportedRequest()) {
            return unsupportedOutcome();
        }

        if (session.getActiveDraft() != null && session.getWorkflowState() == WorkflowState.AWAITING_USER_CONFIRMATION) {
            if (analysis.confirmIntent()) {
                return confirmPayment(profile, session);
            }
            if (analysis.cancelIntent()) {
                return cancelPayment(session);
            }
        }

        if (analysis.cancelIntent() && session.getActiveDraft() != null) {
            return cancelPayment(session);
        }

        if (!analysis.supportedPaymentIntent() && session.getActiveDraft() == null) {
            return unsupportedOutcome();
        }

        PaymentDraft draft = ensureDraft(session);
        mergeAnalysis(draft, analysis);
        return continueJourney(profile, session, draft);
    }

    public TurnOutcome handleUiEvent(Profile profile, ConversationSession session, ApiModels.UiEventRequest request) {
        PaymentDraft draft = session.getActiveDraft();
        if (draft == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHAT2PAY-400", "No active payment draft exists.");
        }

        if (request.eventType() == UiEventType.CLICK_ACTION) {
            String action = firstSelectedItem(request);
            if ("CONFIRM_TRANSFER".equals(action)) {
                return confirmPayment(profile, session);
            }
            if ("CANCEL_TRANSFER".equals(action)) {
                return cancelPayment(session);
            }
        }

        if (request.eventType() == UiEventType.SELECT_ITEM) {
            String selectedItemId = firstSelectedItem(request);
            RegisteredPayee selectedPayee = draft.getCandidatePayees().get(selectedItemId);
            if (selectedPayee == null) {
                return TurnOutcome.of(List.of(
                        blockFactory.error("Unable to continue", "The selected payee is no longer available. Please try again.")));
            }
            applyResolvedPayee(draft, selectedPayee);
            draft.clearCandidatePayees();
            return continueJourney(profile, session, draft);
        }

        if (request.eventType() == UiEventType.SUBMIT_FORM) {
            Map<String, String> values = request.formValues() == null ? Map.of() : request.formValues();
            mergeFormValues(draft, values);
            return continueJourney(profile, session, draft);
        }

        return TurnOutcome.of(List.of(
                blockFactory.error("Unsupported action", "This UI action is not supported in the current POC.")));
    }

    private TurnOutcome continueJourney(Profile profile, ConversationSession session, PaymentDraft draft) {
        session.setJourneyType(JourneyType.DOMESTIC_EXISTING_PAYEE);

        if (draft.getPayeeNameInput() == null || draft.getPayeeNameInput().isBlank() || draft.getAmount() == null) {
            session.setWorkflowState(WorkflowState.COLLECTING_PAYMENT_DETAILS);
            draft.setWorkflowState(WorkflowState.COLLECTING_PAYMENT_DETAILS);
            draft.setStatus(TransferStatus.DRAFT);
            return askForMissingDetails();
        }

        if (draft.getPayeeIdIndex() == null) {
            TurnOutcome resolveOutcome = resolvePayee(profile, session, draft);
            if (resolveOutcome != null) {
                return resolveOutcome;
            }
        }

        draft.setWorkflowState(WorkflowState.AWAITING_USER_CONFIRMATION);
        draft.setStatus(TransferStatus.READY_FOR_CONFIRMATION);
        session.setWorkflowState(WorkflowState.AWAITING_USER_CONFIRMATION);
        session.setTitle("Pay " + draft.getPayeeDisplay());
        draft.setReviewSummary(buildReviewSummary(draft));

        return TurnOutcome.of(List.of(
                        blockFactory.text("Please review the payment details before confirming."),
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

    private TurnOutcome resolvePayee(Profile profile, ConversationSession session, PaymentDraft draft) {
        List<RegisteredPayee> registeredPayees = payeeDirectoryClient.listRegisteredPayees(profile);
        PayeeMatchResult matchResult = payeeMatcher.match(draft.getPayeeNameInput(), registeredPayees);

        if (matchResult.isNone()) {
            draft.setWorkflowState(WorkflowState.COLLECTING_PAYMENT_DETAILS);
            session.setWorkflowState(WorkflowState.COLLECTING_PAYMENT_DETAILS);
            clearResolvedPayee(draft);
            return TurnOutcome.of(List.of(
                    blockFactory.error(
                            "Registered payee not found",
                            "I could not find a registered payee matching \"" + draft.getPayeeNameInput()
                                    + "\". This POC currently supports existing domestic payees only."),
                    missingDetailsForm()));
        }

        if (matchResult.isAmbiguous()) {
            draft.replaceCandidatePayees(matchResult.matches());
            draft.setWorkflowState(WorkflowState.RESOLVING_PAYEE);
            session.setWorkflowState(WorkflowState.RESOLVING_PAYEE);
            return TurnOutcome.of(List.of(
                    blockFactory.text("I found more than one registered payee matching your request."),
                    blockFactory.selectableList(
                            "Select payee",
                            matchResult.matches().stream()
                                    .map(payee -> new ApiModels.SelectableItem(
                                            payee.payeeIdIndex(),
                                            payee.name(),
                                            payee.description(),
                                            null,
                                            Map.of("payeeType", payee.payeeType())))
                                    .toList(),
                            Map.of("stage", "PAYEE_SELECTION"))));
        }

        applyResolvedPayee(draft, matchResult.uniqueMatch());
        draft.clearCandidatePayees();
        return null;
    }

    private TurnOutcome confirmPayment(Profile profile, ConversationSession session) {
        PaymentDraft draft = session.getActiveDraft();
        if (draft == null || draft.getPayeeIdIndex() == null || draft.getAmount() == null) {
            return askForMissingDetails();
        }

        draft.setStatus(TransferStatus.CONFIRMING);
        draft.setWorkflowState(WorkflowState.CONFIRMING_PAYMENT);
        session.setWorkflowState(WorkflowState.CONFIRMING_PAYMENT);

        try {
            PaymentConfirmationResult result = domesticPaymentClient.confirmDomesticPayment(profile, draft);
            draft.setStatus(TransferStatus.CONFIRMED);
            draft.setWorkflowState(WorkflowState.COMPLETED);
            draft.setTransferReference(result.transferReference());
            draft.setDownstreamReferences(new LinkedHashMap<>(result.downstreamPayload()));
            session.setWorkflowState(WorkflowState.COMPLETED);
            session.setStatus(ChatSessionStatus.COMPLETED);

            return TurnOutcome.of(List.of(
                    blockFactory.info("Payment confirmed", "The domestic payment was confirmed successfully."),
                    blockFactory.summary(
                            "Payment completed",
                            List.of(
                                    new ApiModels.DisplayField("Payee", draft.getPayeeDisplay()),
                                    new ApiModels.DisplayField("Amount", formatAmount(draft)),
                                    new ApiModels.DisplayField("Transfer reference", draft.getTransferReference()),
                                    new ApiModels.DisplayField("Status", draft.getStatus().name())),
                            Map.of())));
        } catch (RuntimeException exception) {
            draft.setStatus(TransferStatus.FAILED);
            draft.setWorkflowState(WorkflowState.FAILED);
            session.setWorkflowState(WorkflowState.FAILED);
            return TurnOutcome.of(List.of(
                    blockFactory.error(
                            "Payment failed",
                            "The confirm payment step failed. Please review the draft and try again. "
                                    + exception.getMessage())));
        }
    }

    private TurnOutcome cancelPayment(ConversationSession session) {
        PaymentDraft draft = session.getActiveDraft();
        if (draft != null) {
            draft.setStatus(TransferStatus.CANCELLED);
            draft.setWorkflowState(WorkflowState.CANCELLED);
        }
        session.setWorkflowState(WorkflowState.CANCELLED);
        session.setStatus(ChatSessionStatus.CANCELLED);
        return TurnOutcome.of(List.of(
                blockFactory.info("Payment cancelled", "The active payment draft has been cancelled.")));
    }

    private TurnOutcome askForMissingDetails() {
        return TurnOutcome.of(List.of(
                blockFactory.text("I need the registered payee name and payment amount before I can continue."),
                missingDetailsForm()));
    }

    private TurnOutcome unsupportedOutcome() {
        return TurnOutcome.of(List.of(
                blockFactory.info(
                        "Not supported in this POC",
                        "This POC currently supports domestic payments to existing payees only.")));
    }

    private ApiModels.SimpleFormBlock missingDetailsForm() {
        return blockFactory.simpleForm(
                "Payment details",
                List.of(
                        new ApiModels.FormField("payee", "Registered payee", "TEXT", true, "BOB", List.of()),
                        new ApiModels.FormField("amount", "Amount", "NUMBER", true, "500", List.of()),
                        new ApiModels.FormField(
                                "currency",
                                "Currency",
                                "SELECT",
                                true,
                                null,
                                List.of(new ApiModels.SelectableItem("HKD", "HKD", null, null, Map.of()))),
                        new ApiModels.FormField("note", "Note", "TEXT", false, "Optional note", List.of())),
                "Continue",
                Map.of("stage", "COLLECT_PAYMENT_DETAILS"));
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
        return draft;
    }

    private void mergeAnalysis(PaymentDraft draft, MessageAnalysis analysis) {
        if (analysis.payeeName() != null && !analysis.payeeName().isBlank()) {
            draft.setPayeeNameInput(analysis.payeeName().trim());
            if (!analysis.payeeName().trim().equalsIgnoreCase(draft.getPayeeDisplay())) {
                clearResolvedPayee(draft);
            }
        }
        if (analysis.amount() != null) {
            draft.setAmount(analysis.amount());
        }
        if (analysis.currency() != null && !analysis.currency().isBlank()) {
            draft.setCurrency(analysis.currency().toUpperCase());
        }
        if (analysis.note() != null && !analysis.note().isBlank()) {
            draft.setNote(analysis.note());
        }
    }

    private void mergeFormValues(PaymentDraft draft, Map<String, String> values) {
        String payee = values.get("payee");
        if (payee != null && !payee.isBlank()) {
            draft.setPayeeNameInput(payee.trim());
            if (!payee.trim().equalsIgnoreCase(draft.getPayeeDisplay())) {
                clearResolvedPayee(draft);
            }
        }

        String amount = values.get("amount");
        if (amount != null && !amount.isBlank()) {
            draft.setAmount(new BigDecimal(amount.trim()));
        }

        String currency = values.get("currency");
        if (currency != null && !currency.isBlank()) {
            draft.setCurrency(currency.trim().toUpperCase());
        }

        String note = values.get("note");
        if (note != null && !note.isBlank()) {
            draft.setNote(note.trim());
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
        List<ApiModels.DisplayField> fields = new java.util.ArrayList<>();
        fields.add(new ApiModels.DisplayField("Source account", draft.getSourceAccountDisplay()));
        fields.add(new ApiModels.DisplayField("Payee", draft.getPayeeDisplay()));
        fields.add(new ApiModels.DisplayField("Amount", formatAmount(draft)));
        if (draft.getNote() != null && !draft.getNote().isBlank()) {
            fields.add(new ApiModels.DisplayField("Note", draft.getNote()));
        }
        return List.copyOf(fields);
    }

    private String formatAmount(PaymentDraft draft) {
        return draft.getAmount().stripTrailingZeros().toPlainString() + " " + draft.getCurrency();
    }

    private String firstSelectedItem(ApiModels.UiEventRequest request) {
        if (request.selectedItemIds() == null || request.selectedItemIds().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CHAT2PAY-400", "Selected item is required.");
        }
        return request.selectedItemIds().getFirst();
    }
}
