package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ErrorSummary;
import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.api.dto.ContentBlock.DisplayField;
import com.chat2pay.app.api.dto.ContentBlock.SelectableItem;
import com.chat2pay.app.application.conversation.intent.IntentAnalysis;
import com.chat2pay.app.application.conversation.intent.IntentType;
import com.chat2pay.app.application.conversation.tool.PaymentToolContext;
import com.chat2pay.app.application.conversation.tool.PaymentToolExecution;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient.DomesticPaymentRequest;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient.PaymentConfirmationResult;
import com.chat2pay.app.persistence.repository.PayeeStore;
import com.chat2pay.app.persistence.repository.PayeeStore.RegisteredPayee;
import com.chat2pay.app.persistence.repository.ProfileStore;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

@Service
public class DomesticPaymentJourneyService {

    private static final Logger log = LoggerFactory.getLogger(DomesticPaymentJourneyService.class);

    private final PayeeStore payees;
    private final DomesticPaymentClient domesticPayments;
    private final ProfileStore profiles;
    private final ChatBlockFactory blocks;
    private final ConversationStateMachine stateMachine;
    private final PaymentPolicyGuard policyGuard;

    public DomesticPaymentJourneyService(PayeeStore payees,
                                         DomesticPaymentClient domesticPayments,
                                         ProfileStore profiles,
                                         ChatBlockFactory blocks,
                                         ConversationStateMachine stateMachine,
                                         PaymentPolicyGuard policyGuard) {
        this.payees = payees;
        this.domesticPayments = domesticPayments;
        this.profiles = profiles;
        this.blocks = blocks;
        this.stateMachine = stateMachine;
        this.policyGuard = policyGuard;
    }

    public ChatMessage handlePayeeLookup(SessionRecord record, String query) {
        try {
            PayeeLookupView view = buildPayeeLookupView(record, query);
            return assistantMessage(record.session().sessionId(), view.blocks());
        } catch (RuntimeException ex) {
            return payeeLookupFailed(record, ex);
        }
    }

    public PaymentToolExecution executeRegisteredPayeesTool(PaymentToolContext context, String query) {
        try {
            PayeeLookupView view = buildPayeeLookupView(context.record(), query);
            Map<String, Object> result = new HashMap<>();
            result.put("ok", true);
            if (query != null) result.put("query", query);
            result.put("match_count", view.matches().size());
            result.put("payees", view.matches().stream()
                    .map(p -> Map.of(
                            "payee_id", p.summary().payeeId(),
                            "name", p.summary().name(),
                            "bank_code", p.summary().bankCode(),
                            "bank_name", p.summary().bankName(),
                            "account_number", p.summary().accountNumber(),
                            "display_label", p.summary().displayLabel()
                    ))
                    .toList());
            return new PaymentToolExecution(context.toolCall().id(), context.toolCall().name(),
                    result, null, view.blocks());
        } catch (RuntimeException ex) {
            return new PaymentToolExecution(context.toolCall().id(), context.toolCall().name(),
                    Map.of("ok", false, "error", "registered_payee_lookup_failed",
                            "message", downstreamMessage(ex)),
                    payeeLookupFailed(context.record(), ex), List.of());
        }
    }

    public ChatMessage continueDomesticPayment(SessionRecord record, IntentAnalysis intent) {
        PaymentDraft draft = record.session().activeDraft() != null
                ? record.session().activeDraft()
                : stateMachine.ensureDraft(record, profileCurrency(record.profileId()));

        String payeeQuery = intent.payeeQuery();
        boolean payeeChanged = isNewPayeeQuery(draft, payeeQuery);
        BigDecimal amount = payeeChanged ? null : intent.amount() != null ? intent.amount() : draft.amount();
        LocalDate date = payeeChanged ? null : intent.paymentDate() != null ? intent.paymentDate() : draft.paymentDate();

        draft = stateMachine.updateDraft(draft,
                payeeQuery != null ? payeeQuery : draft.payeeQueryText(),
                payeeChanged ? null : draft.selectedPayee(),
                amount,
                date,
                draft.status(), null);
        record.setSession(stateMachine.withDraft(record.session(), draft));

        if (draft.selectedPayee() != null) {
            if (draft.amount() == null || draft.paymentDate() == null) return askForMissingDetails(record, draft);
            return prepareConfirmation(record, draft);
        }

        if (draft.payeeQueryText() == null) return askForMissingDetails(record, draft);

        List<RegisteredPayee> matches;
        try {
            matches = payees.findByQuery(record.profileId(), draft.payeeQueryText());
        } catch (RuntimeException ex) {
            return payeeLookupFailed(record, ex);
        }
        if (matches.isEmpty()) {
            stateMachine.transition(record, ConversationState.COLLECTING_DETAILS, ChatSessionStatus.ACTIVE);
            stateMachine.titleFromDraft(record, draft);
            Map<String, Object> metadata = Map.of(
                    "editableFields", List.of(editablePaymentDateField(draft, true))
            );
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("Registered payee not found",
                            "I could not find a registered payee matching \"" + draft.payeeQueryText()
                                    + "\". Please try another payee name."),
                    summaryBlock("Current draft", draftFields(draft), metadata)
            ));
        }

        if (matches.size() > 1) {
            stateMachine.transition(record, ConversationState.AWAITING_PAYEE_SELECTION, ChatSessionStatus.ACTIVE);
            stateMachine.titleFromDraft(record, draft);
            List<SelectableItem> items = matches.stream().map(p ->
                    new SelectableItem(p.summary().payeeId(), p.summary().name(),
                            p.summary().bankName() + " • " + p.summary().displayLabel(),
                            null, payeeMetadata(p.summary()))).toList();
            return assistantMessage(record.session().sessionId(), List.of(
                    textBlock("Choose payee",
                            "I found more than one registered payee for \"" + draft.payeeQueryText()
                                    + "\". Please choose the correct one."),
                    blocks.selectableListBlock("Registered payee matches",
                            items, Map.of("purpose", "payee-selection"))
            ));
        }

        draft = stateMachine.updateDraft(draft, draft.payeeQueryText(), matches.get(0).summary(),
                draft.amount(), draft.paymentDate(), draft.status(), null);
        record.setSession(stateMachine.withDraft(record.session(), draft));

        if (draft.amount() == null || draft.paymentDate() == null) return askForMissingDetails(record, draft);
        return prepareConfirmation(record, draft);
    }

    public ChatMessage selectPayee(SessionRecord record, String selectedId) {
        if (selectedId == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Invalid selection", "No item was selected.")));
        }
        var payee = payees.findById(record.profileId(), selectedId).orElse(null);
        PaymentDraft draft = record.session().activeDraft();
        if (payee == null || draft == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Selection expired", "The selected payee is no longer available.")));
        }

        boolean payeeChanged = draft.selectedPayee() != null
                && !draft.selectedPayee().payeeId().equals(payee.summary().payeeId());
        draft = stateMachine.updateDraft(draft, draft.payeeQueryText(), payee.summary(),
                payeeChanged ? null : draft.amount(),
                payeeChanged ? null : draft.paymentDate(),
                draft.status(), null);
        record.setSession(stateMachine.withDraft(record.session(), draft));
        if (draft.amount() == null || draft.paymentDate() == null) return askForMissingDetails(record, draft);
        return prepareConfirmation(record, draft);
    }

    public ChatMessage submitDetails(SessionRecord record, Map<String, String> formValues) {
        PaymentDraft draft = record.session().activeDraft();
        if (draft == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("No active draft", "Start a payment request before submitting details.")));
        }
        Map<String, String> values = formValues == null ? Map.of() : formValues;
        String payeeQuery = trim(values.get("payee"));
        String amountText = trim(values.get("amount"));
        String paymentDate = trim(values.get("paymentDate"));

        BigDecimal amount = null;
        if (amountText != null) {
            try {
                BigDecimal parsed = new BigDecimal(amountText);
                if (parsed.signum() > 0) amount = parsed;
            } catch (NumberFormatException ignored) { }
        }
        LocalDate date = null;
        if (paymentDate != null) {
            try { date = LocalDate.parse(paymentDate); } catch (Exception ignored) { }
        }

        return continueDomesticPayment(record, new IntentAnalysis(
                IntentType.DOMESTIC_PAYMENT,
                IntentAnalysis.toolNameFor(IntentType.DOMESTIC_PAYMENT),
                payeeQuery,
                amount,
                date,
                "UI_EVENT"
        ));
    }

    public ChatMessage executePayment(SessionRecord record) {
        PaymentDraft draft = record.session().activeDraft();
        PaymentPolicyGuard.PolicyDecision decision = policyGuard.requireCompleteDomesticDraft(draft);
        if (!decision.allowed()) {
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Unable to execute",
                            decision.message())));
        }

        draft = stateMachine.updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                draft.paymentDate(), PaymentDraftStatus.EXECUTING, draft.downstreamReference());
        record.setSession(stateMachine.withDraft(record.session(), draft));
        stateMachine.transition(record, ConversationState.EXECUTING, ChatSessionStatus.ACTIVE);

        PaymentConfirmationResult result;
        try {
            result = domesticPayments.confirm(new DomesticPaymentRequest(
                    record.profileId(),
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
            log.warn("Downstream domestic payment confirmation failed: profileId={} sessionId={} draftId={} message={}",
                    record.profileId(), record.session().sessionId(), draft.draftId(), ex.getMessage());
            log.debug("Downstream domestic payment confirmation failure details", ex);
            Map<String, Object> context = withContext(draft.context(), Map.of(
                    "downstreamError", ex.getMessage() == null ? "Unknown downstream error" : ex.getMessage()
            ));
            draft = stateMachine.updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                    draft.paymentDate(), PaymentDraftStatus.FAILED, draft.downstreamReference(),
                    new ErrorSummary("DOWNSTREAM_PAYMENT_FAILED", ex.getMessage()), context);
            record.setSession(stateMachine.withDraft(record.session(), draft));
            stateMachine.transition(record, ConversationState.FAILED, ChatSessionStatus.FAILED);
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
        draft = stateMachine.updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                draft.paymentDate(), PaymentDraftStatus.CONFIRMED, reference, null, context);
        record.setSession(stateMachine.withDraft(record.session(), draft));
        stateMachine.transition(record, ConversationState.COMPLETED, ChatSessionStatus.COMPLETED);

        List<DisplayField> fields = new ArrayList<>(draftFields(draft));
        fields.add(new DisplayField("Reference", reference));

        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Payment submitted",
                        "Your domestic payment to " + draft.selectedPayee().name()
                                + " has been submitted successfully."),
                summaryBlock("Completed payment", fields, null)
        ));
    }

    /**
     * Update only the payment date on the active draft. Used when the user picks a new date
     * via the inline date picker on the confirmation card and clicks Confirm — we apply the
     * change before executing so the downstream call uses the requested date.
     */
    public void updatePaymentDate(SessionRecord record, LocalDate paymentDate) {
        PaymentDraft draft = record.session().activeDraft();
        if (draft == null || paymentDate == null) return;
        if (paymentDate.equals(draft.paymentDate())) return;
        draft = stateMachine.updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(),
                draft.amount(), paymentDate, draft.status(), draft.downstreamReference());
        record.setSession(stateMachine.withDraft(record.session(), draft));
    }

    public ChatMessage cancelPayment(SessionRecord record) {
        PaymentDraft draft = record.session().activeDraft();
        if (draft == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("No active draft", "There is no active domestic payment draft to cancel.")));
        }
        draft = stateMachine.updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                draft.paymentDate(), PaymentDraftStatus.CANCELLED, draft.downstreamReference());
        record.setSession(stateMachine.withDraft(record.session(), draft));
        stateMachine.transition(record, ConversationState.CANCELLED, ChatSessionStatus.CANCELLED);
        String who = draft.selectedPayee() != null ? draft.selectedPayee().name()
                : draft.payeeQueryText() != null ? draft.payeeQueryText() : "the selected payee";
        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Payment cancelled",
                        "The domestic payment draft for " + who + " was cancelled."),
                summaryBlock("Cancelled draft", draftFields(draft), null)
        ));
    }

    public ChatMessage unsupportedCrossBorderPayment(SessionRecord record) {
        stateMachine.transition(record, ConversationState.IDLE, ChatSessionStatus.ACTIVE);
        PaymentPolicyGuard.PolicyDecision decision = policyGuard.crossBorderUnavailableInV1();
        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Not supported in V1", decision.message())
        ));
    }

    public ChatMessage explicitConfirmationRequired(SessionRecord record,
                                                    PaymentPolicyGuard.PolicyDecision decision) {
        PaymentDraft draft = record.session().activeDraft();
        List<ContentBlock> content = new ArrayList<>();
        content.add(infoBlock("Explicit confirmation required", decision.message()));
        if (draft != null) content.add(summaryBlock("Current draft", draftFields(draft), null));
        return assistantMessage(record.session().sessionId(), content);
    }

    private ChatMessage askForMissingDetails(SessionRecord record, PaymentDraft draft) {
        draft = stateMachine.updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(),
                draft.amount(), draft.paymentDate(), PaymentDraftStatus.DRAFT, null);
        record.setSession(stateMachine.withDraft(record.session(), draft));
        stateMachine.transition(record, ConversationState.COLLECTING_DETAILS, ChatSessionStatus.ACTIVE);
        stateMachine.titleFromDraft(record, draft);

        List<String> missing = new ArrayList<>();
        if (draft.payeeQueryText() == null && draft.selectedPayee() == null) missing.add("payee");
        if (draft.amount() == null) missing.add("amount");
        if (draft.paymentDate() == null) missing.add("payment date");

        String prompt = missing.size() == 1
                ? "I still need the " + missing.get(0) + " before I can prepare the domestic payment."
                : "I still need these details before I can prepare the domestic payment: "
                        + String.join(", ", missing) + ".";

        // Same date-picker affordance on the Current draft card so the user can fill in the
        // payment date by clicking the control instead of typing. submitOnChange=true means the
        // FE auto-fires a SUBMIT_FORM event on pick, so the next assistant turn shows the date
        // applied. The chat input still works for typing dates the LLM understands.
        Map<String, Object> metadata = Map.of(
                "editableFields", List.of(editablePaymentDateField(draft, true))
        );

        return assistantMessage(record.session().sessionId(), List.of(
                textBlock("Need more details", prompt),
                summaryBlock("Current draft", draftFields(draft), metadata)
        ));
    }

    private Map<String, Object> editablePaymentDateField(PaymentDraft draft, boolean submitOnChange) {
        Map<String, Object> field = new HashMap<>();
        field.put("label", "Payment date");
        field.put("fieldId", "paymentDate");
        field.put("fieldType", "DATE");
        field.put("value", draft.paymentDate() == null ? "" : draft.paymentDate().toString());
        field.put("minDate", LocalDate.now().toString());
        field.put("submitOnChange", submitOnChange);
        return field;
    }

    private static boolean isNewPayeeQuery(PaymentDraft draft, String payeeQuery) {
        if (payeeQuery == null || payeeQuery.isBlank()) return false;
        String next = normalizePayee(payeeQuery);
        if (next == null) return false;
        if (draft.payeeQueryText() == null && draft.selectedPayee() == null) return false;
        if (samePayeeText(next, draft.payeeQueryText())) return false;
        return draft.selectedPayee() == null || !samePayeeText(next, draft.selectedPayee().name());
    }

    private static boolean samePayeeText(String normalized, String value) {
        String current = normalizePayee(value);
        return current != null && current.equals(normalized);
    }

    private static String normalizePayee(String value) {
        if (value == null) return null;
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isBlank() ? null : normalized;
    }

    private ChatMessage prepareConfirmation(SessionRecord record, PaymentDraft draft) {
        draft = stateMachine.updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                draft.paymentDate(), PaymentDraftStatus.AWAITING_CONFIRMATION, null);
        record.setSession(stateMachine.withDraft(record.session(), draft));
        stateMachine.transition(record, ConversationState.AWAITING_CONFIRMATION, ChatSessionStatus.ACTIVE);
        stateMachine.titleFromDraft(record, draft);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("actions", List.of(
                Map.of("id", "CONFIRM_PAYMENT", "label", "Confirm payment"),
                Map.of("id", "CANCEL_PAYMENT", "label", "Cancel", "tone", "secondary")
        ));
        // Hint to the FE that the payment-date field can be edited inline. submitOnChange=false
        // means the picked value is held locally and only sent on the CONFIRM_PAYMENT click, so
        // the user does not generate noisy turns while reviewing the draft.
        metadata.put("editableFields", List.of(editablePaymentDateField(draft, false)));

        return assistantMessage(record.session().sessionId(), List.of(
                textBlock("Awaiting confirmation",
                        "Please confirm the payee, amount, and payment date before I submit the domestic payment."),
                summaryBlock("Domestic payment summary", draftFields(draft), metadata)
        ));
    }

    private PayeeLookupView buildPayeeLookupView(SessionRecord record, String query) {
        List<RegisteredPayee> matches = query != null
                ? payees.findByQuery(record.profileId(), query)
                : payees.all(record.profileId());

        stateMachine.transition(record, ConversationState.IDLE, ChatSessionStatus.ACTIVE);
        if (query != null) stateMachine.setTitle(record, "Find " + stateMachine.titleCase(query));
        else stateMachine.setTitle(record, "Registered payees");

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
                summaryBlock(matches.size() == 1 ? "Registered payee" : "Registered payee results",
                        fields,
                        Map.of(
                                "purpose", "registered-payee-results",
                                "payeeCount", matches.size(),
                                "payees", matches.stream().map(p -> payeeMetadata(p.summary())).toList()
                        ))
        ));
    }

    private ChatMessage payeeLookupFailed(SessionRecord record, RuntimeException ex) {
        log.warn("Registered payee lookup failed: profileId={} sessionId={} message={}",
                record.profileId(), record.session().sessionId(), ex.getMessage());
        log.debug("Registered payee lookup failure details", ex);
        stateMachine.transition(record, ConversationState.IDLE, ChatSessionStatus.ACTIVE);
        return assistantMessage(record.session().sessionId(), List.of(
                errorBlock("Registered payee lookup failed",
                        "I could not retrieve registered payees right now. Please try again after downstream access is restored.")
        ));
    }

    private String profileCurrency(String profileId) {
        return profiles.runtimeProfile(profileId).requiredPaymentCurrency();
    }

    private Map<String, Object> withContext(Map<String, Object> existing, Map<String, Object> updates) {
        Map<String, Object> next = new HashMap<>();
        if (existing != null) next.putAll(existing);
        next.putAll(updates);
        return next;
    }

    private static String downstreamMessage(RuntimeException ex) {
        return ex.getMessage() == null ? "Unknown downstream error" : ex.getMessage();
    }

    private ContentBlock.TextBlock textBlock(String title, String text) {
        return blocks.textBlock(title, text);
    }

    private ContentBlock.InfoCardBlock infoBlock(String title, String text) {
        return blocks.infoBlock(title, text);
    }

    private ContentBlock.ErrorCardBlock errorBlock(String title, String text) {
        return blocks.errorBlock(title, text);
    }

    private ContentBlock.SummaryCardBlock summaryBlock(String title, List<DisplayField> fields,
                                                       Map<String, Object> metadata) {
        return blocks.summaryBlock(title, fields, metadata);
    }

    private List<DisplayField> draftFields(PaymentDraft d) {
        return blocks.draftFields(d);
    }

    private static Map<String, Object> payeeMetadata(PayeeSummary p) {
        Map<String, Object> metadata = new HashMap<>();
        putIfPresent(metadata, "payeeId", p.payeeId());
        putIfPresent(metadata, "name", p.name());
        putIfPresent(metadata, "payeeType", p.payeeType());
        putIfPresent(metadata, "bankCode", p.bankCode());
        putIfPresent(metadata, "bankName", p.bankName());
        putIfPresent(metadata, "accountNumber", p.accountNumber());
        putIfPresent(metadata, "displayLabel", p.displayLabel());
        return metadata;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private ChatMessage assistantMessage(String sessionId, List<ContentBlock> contentBlocks) {
        return blocks.assistantMessage(sessionId, contentBlocks);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private record PayeeLookupView(List<RegisteredPayee> matches, List<ContentBlock> blocks) {}
}
