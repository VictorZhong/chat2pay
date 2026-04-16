package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.MessageAnalysis;
import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.integration.downstream.RegisteredPayee;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HeuristicJourneyAgentPlanner implements JourneyAgentPlanner {

    public static final String TOOL_BROWSE_REGISTERED_PAYEES = "browse_registered_payees";
    public static final String TOOL_LIST_REGISTERED_PAYEES = "list_registered_payees";
    public static final String TOOL_CONFIRM_DOMESTIC_PAYMENT = "confirm_domestic_payment";

    public static final String MATCH_STATUS_KEY = "matchStatus";
    public static final String MATCH_NONE = "NONE";
    public static final String MATCH_UNIQUE = "UNIQUE";
    public static final String MATCH_AMBIGUOUS = "AMBIGUOUS";
    public static final String CONFIRMATION_STATUS_KEY = "confirmationStatus";
    public static final String CONFIRMATION_SUCCESS = "SUCCESS";

    private final HeuristicMessageAnalyzer heuristicMessageAnalyzer;

    public HeuristicJourneyAgentPlanner(HeuristicMessageAnalyzer heuristicMessageAnalyzer) {
        this.heuristicMessageAnalyzer = heuristicMessageAnalyzer;
    }

    @Override
    public JourneyAgentDecision plan(JourneyAgentContext context) {
        JourneyUserSignal signal = context.userSignal();
        MessageAnalysis analysis = signal.analysis() != null
                ? signal.analysis()
                : heuristicMessageAnalyzer.analyze(signal.rawMessage());
        JourneyDraftUpdate update = buildDraftUpdate(signal, analysis);
        PaymentDraft draft = context.draft();
        EffectiveDraftState effectiveDraft = EffectiveDraftState.from(draft, update);
        JourneyToolResult latestToolResult = context.latestToolResult();

        if (latestToolResult != null) {
            if (!latestToolResult.success()) {
                return new JourneyAgentDecision(
                        JourneyAction.FAIL,
                        "The last backend step failed. Please review the details and try again.",
                        null,
                        List.of(),
                        update);
            }

            if (TOOL_BROWSE_REGISTERED_PAYEES.equals(latestToolResult.toolName())) {
                return new JourneyAgentDecision(
                        JourneyAction.SHOW_PAYEE_LIST,
                        "Here are your registered payees. Select one to view its details.",
                        null,
                        List.of(),
                        update);
            }

            if (TOOL_LIST_REGISTERED_PAYEES.equals(latestToolResult.toolName())) {
                String matchStatus = String.valueOf(latestToolResult.payload().getOrDefault(MATCH_STATUS_KEY, ""));
                if (MATCH_NONE.equals(matchStatus)) {
                    return new JourneyAgentDecision(
                            JourneyAction.ASK_USER,
                            "I couldn't find a registered payee with that name. Please provide the registered payee name again.",
                            null,
                            List.of("payeeName"),
                            update);
                }

                if (MATCH_AMBIGUOUS.equals(matchStatus)) {
                    return new JourneyAgentDecision(
                            JourneyAction.ASK_USER,
                            "I found more than one registered payee. Please choose the intended payee.",
                            null,
                            List.of("selectedPayeeId"),
                            update);
                }

                if (MATCH_UNIQUE.equals(matchStatus)) {
                    if (effectiveDraft.amount() == null) {
                        return new JourneyAgentDecision(
                                JourneyAction.ASK_USER,
                                "I found the payee. I still need the payment amount before I can continue.",
                                null,
                                List.of("amount"),
                                update);
                    }

                    return new JourneyAgentDecision(
                            JourneyAction.SHOW_CONFIRMATION,
                            "I found the registered payee. Please review the payment details before confirming.",
                            null,
                            List.of(),
                            update);
                }
            }

            if (TOOL_CONFIRM_DOMESTIC_PAYMENT.equals(latestToolResult.toolName())) {
                String confirmationStatus = String.valueOf(latestToolResult.payload().getOrDefault(CONFIRMATION_STATUS_KEY, ""));
                if (CONFIRMATION_SUCCESS.equals(confirmationStatus)) {
                    return new JourneyAgentDecision(
                            JourneyAction.COMPLETE,
                            "The domestic payment has been confirmed successfully.",
                            null,
                            List.of(),
                            update);
                }

                return new JourneyAgentDecision(
                        JourneyAction.FAIL,
                        "The confirm payment step did not succeed.",
                        null,
                        List.of(),
                        update);
            }
        }

        if (signal.explicitCancellationRequested()) {
            return new JourneyAgentDecision(
                    JourneyAction.CANCEL,
                    "The active payment draft has been cancelled.",
                    null,
                    List.of(),
                    update);
        }

        if (analysis != null && analysis.createPayeeIntent()) {
            return new JourneyAgentDecision(
                    JourneyAction.UNSUPPORTED,
                    "Creating a new payee is not supported in this POC yet. I can still show your registered payees or help you pay an existing payee.",
                    null,
                    List.of(),
                    update);
        }

        if (analysis != null && analysis.browsePayeesIntent()) {
            return new JourneyAgentDecision(
                    JourneyAction.CALL_TOOL,
                    "I'll fetch your registered payees now.",
                    TOOL_BROWSE_REGISTERED_PAYEES,
                    List.of(),
                    update);
        }

        if (analysis != null && analysis.unsupportedRequest()) {
            return new JourneyAgentDecision(
                    JourneyAction.UNSUPPORTED,
                    "This POC currently supports browsing registered payees and domestic payments to existing payees only.",
                    null,
                    List.of(),
                    update);
        }

        if (signal.explicitPayeeChangeRequested() && update.payeeNameInput() == null) {
            return new JourneyAgentDecision(
                    JourneyAction.ASK_USER,
                    "Sure. Which registered payee would you like instead?",
                    null,
                    List.of("payeeName"),
                    new JourneyDraftUpdate(
                            update.payeeNameInput(),
                            update.amount(),
                            update.currency(),
                            update.note(),
                            update.selectedPayeeId(),
                            true));
        }

        if (signal.explicitConfirmationRequested()) {
            if (effectiveDraft.payeeIdIndex() == null || effectiveDraft.amount() == null) {
                return new JourneyAgentDecision(
                        JourneyAction.ASK_USER,
                        "I still need the registered payee and payment amount before I can confirm the payment.",
                        null,
                        missingInputs(effectiveDraft),
                        update);
            }

            return new JourneyAgentDecision(
                    JourneyAction.CALL_TOOL,
                    "I'll submit the payment confirmation now.",
                    TOOL_CONFIRM_DOMESTIC_PAYMENT,
                    List.of(),
                    update);
        }

        if (!signal.hasStartedJourneySignal() && context.draft() == null) {
            return new JourneyAgentDecision(
                    JourneyAction.UNSUPPORTED,
                    "This POC currently supports browsing registered payees and domestic payments to existing payees only.",
                    null,
                    List.of(),
                    update);
        }

        if (effectiveDraft.payeeNameInput() == null || effectiveDraft.amount() == null) {
            return new JourneyAgentDecision(
                    JourneyAction.ASK_USER,
                    "I need the registered payee name and payment amount before I can continue.",
                    null,
                    missingInputs(effectiveDraft),
                    update);
        }

        if (effectiveDraft.payeeIdIndex() == null) {
            return new JourneyAgentDecision(
                    JourneyAction.CALL_TOOL,
                    "I'll check the registered payee list first.",
                    TOOL_LIST_REGISTERED_PAYEES,
                    List.of(),
                    update);
        }

        return new JourneyAgentDecision(
                JourneyAction.SHOW_CONFIRMATION,
                "Please review the payment details before confirming.",
                null,
                List.of(),
                update);
    }

    private JourneyDraftUpdate buildDraftUpdate(JourneyUserSignal signal, MessageAnalysis analysis) {
        Map<String, String> formValues = signal.formValues() == null ? Map.of() : signal.formValues();
        String payeeName = coalesce(
                emptyToNull(formValues.get("payee")),
                analysis != null ? emptyToNull(analysis.payeeName()) : null);
        BigDecimal amount = formValues.containsKey("amount") && formValues.get("amount") != null && !formValues.get("amount").isBlank()
                ? new BigDecimal(formValues.get("amount").trim())
                : analysis != null ? analysis.amount() : null;
        String currency = coalesce(
                emptyToNull(formValues.get("currency")),
                analysis != null ? emptyToNull(analysis.currency()) : null);
        String note = coalesce(
                emptyToNull(formValues.get("note")),
                analysis != null ? emptyToNull(analysis.note()) : null);

        return new JourneyDraftUpdate(
                payeeName,
                amount,
                currency,
                note,
                signal.selectedItemId(),
                false);
    }

    private List<String> missingInputs(EffectiveDraftState effectiveDraft) {
        List<String> missing = new ArrayList<>();
        if (effectiveDraft.payeeNameInput() == null || effectiveDraft.payeeNameInput().isBlank()) {
            missing.add("payeeName");
        }
        if (effectiveDraft.amount() == null) {
            missing.add("amount");
        }
        if (effectiveDraft.currency() == null || effectiveDraft.currency().isBlank()) {
            missing.add("currency");
        }
        return missing.isEmpty() ? List.of("payeeName", "amount") : List.copyOf(missing);
    }

    private String coalesce(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record EffectiveDraftState(
            String payeeNameInput,
            BigDecimal amount,
            String currency,
            String payeeIdIndex) {

        private static EffectiveDraftState from(PaymentDraft draft, JourneyDraftUpdate update) {
            RegisteredPayee selectedPayee = draft == null || update.selectedPayeeId() == null
                    ? null
                    : draft.getCandidatePayees().get(update.selectedPayeeId());
            String payeeNameInput = update.resetPayeeSelection()
                    ? null
                    : update.payeeNameInput() != null && !update.payeeNameInput().isBlank()
                            ? update.payeeNameInput().trim()
                            : draft != null ? draft.getPayeeNameInput() : null;
            BigDecimal amount = update.amount() != null
                    ? update.amount()
                    : draft != null ? draft.getAmount() : null;
            String currency = update.currency() != null && !update.currency().isBlank()
                    ? update.currency().trim().toUpperCase()
                    : draft != null && draft.getCurrency() != null && !draft.getCurrency().isBlank()
                            ? draft.getCurrency()
                            : amount != null ? "HKD" : null;
            String payeeIdIndex = update.resetPayeeSelection()
                    ? null
                    : selectedPayee != null
                            ? selectedPayee.payeeIdIndex()
                            : draft != null ? draft.getPayeeIdIndex() : null;
            return new EffectiveDraftState(payeeNameInput, amount, currency, payeeIdIndex);
        }
    }
}
