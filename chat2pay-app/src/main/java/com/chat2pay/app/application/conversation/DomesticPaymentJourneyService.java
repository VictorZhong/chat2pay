package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.DebitAccountSummary;
import com.chat2pay.app.api.dto.ChatDtos.ErrorSummary;
import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.api.dto.ContentBlock.DisplayField;
import com.chat2pay.app.application.conversation.intent.IntentAnalysis;
import com.chat2pay.app.application.conversation.intent.IntentType;
import com.chat2pay.app.application.conversation.tool.PaymentToolContext;
import com.chat2pay.app.application.conversation.tool.PaymentToolExecution;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.integration.downstream.account.DomesticAccountClient;
import com.chat2pay.app.integration.downstream.account.DomesticAccountClient.AccountGroup;
import com.chat2pay.app.integration.downstream.account.DomesticAccountClient.ParentAccount;
import com.chat2pay.app.integration.downstream.account.DomesticAccountClient.SelectableAccount;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient.DomesticPaymentRequest;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient.PaymentConfirmationResult;
import com.chat2pay.app.persistence.repository.PayeeStore;
import com.chat2pay.app.persistence.repository.PayeeStore.PayeeAccountRef;
import com.chat2pay.app.persistence.repository.PayeeStore.RegisteredAccount;
import com.chat2pay.app.persistence.repository.PayeeStore.RegisteredPayee;
import com.chat2pay.app.persistence.repository.ProfileStore;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class DomesticPaymentJourneyService {

    private static final Logger log = LoggerFactory.getLogger(DomesticPaymentJourneyService.class);

    private final PayeeStore payees;
    private final DomesticPaymentClient domesticPayments;
    private final DomesticAccountClient debitAccounts;
    private final ProfileStore profiles;
    private final ChatBlockFactory blocks;
    private final ConversationStateMachine stateMachine;
    private final PaymentPolicyGuard policyGuard;

    public DomesticPaymentJourneyService(PayeeStore payees,
                                         DomesticPaymentClient domesticPayments,
                                         DomesticAccountClient debitAccounts,
                                         ProfileStore profiles,
                                         ChatBlockFactory blocks,
                                         ConversationStateMachine stateMachine,
                                         PaymentPolicyGuard policyGuard) {
        this.payees = payees;
        this.domesticPayments = domesticPayments;
        this.debitAccounts = debitAccounts;
        this.profiles = profiles;
        this.blocks = blocks;
        this.stateMachine = stateMachine;
        this.policyGuard = policyGuard;
    }

    public ChatMessage handleDebitAccountLookup(SessionRecord record) {
        List<AccountGroup> groups = debitAccounts.loadAccounts(record.profileId());
        stateMachine.transition(record, ConversationState.IDLE, ChatSessionStatus.ACTIVE);
        stateMachine.setTitle(record, "My debit accounts");
        if (selectableAccounts(groups).isEmpty()) {
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("No debit accounts available",
                            "I could not find any debit accounts for this profile.")
            ));
        }
        return assistantMessage(record.session().sessionId(), buildDebitAccountLookupBlocks(groups));
    }

    public PaymentToolExecution executeListDebitAccountsTool(PaymentToolContext context) {
        List<AccountGroup> groups = debitAccounts.loadAccounts(context.record().profileId());
        List<SelectableAccount> accounts = selectableAccounts(groups);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("group_count", groups.size());
        result.put("account_count", accounts.size());
        result.put("groups", groups.stream().map(this::debitAccountGroupToolMap).toList());
        ChatMessage terminal = assistantMessage(context.record().session().sessionId(),
                buildDebitAccountLookupBlocks(groups));
        return new PaymentToolExecution(context.toolCall().id(), context.toolCall().name(),
                result, terminal, List.of());
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
            result.put("payee_count", view.matches().size());
            result.put("account_count", view.matches().stream()
                    .mapToInt(p -> p.accounts().size()).sum());
            result.put("payees", view.matches().stream()
                    .map(p -> Map.of(
                            "nick_name", nullSafe(p.nickName()),
                            "contact_full_name", nullSafe(p.contactFullName()),
                            "account_count", p.accounts().size(),
                            "accounts", p.accounts().stream().map(this::accountToToolMap).toList()
                    ))
                    .toList());
            // Short-circuit: render the directory now so the LLM does not get a chance to also
            // dump payee names in plain text on a follow-up turn (which is what produced the
            // "Your registered payees are: ..." paragraph the user disliked).
            ChatMessage terminal = assistantMessage(context.record().session().sessionId(), view.blocks());
            return new PaymentToolExecution(context.toolCall().id(), context.toolCall().name(),
                    result, terminal, List.of());
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
        BigDecimal amount = intent.amount() != null ? intent.amount() : draft.amount();
        LocalDate date = intent.paymentDate() != null ? intent.paymentDate() : draft.paymentDate();

        draft = stateMachine.updateDraft(draft,
                payeeQuery != null ? payeeQuery : draft.payeeQueryText(),
                payeeChanged ? null : draft.selectedPayee(),
                amount,
                date,
                draft.status(), null);
        record.setSession(stateMachine.withDraft(record.session(), draft));

        if (draft.selectedPayee() != null) {
            if (draft.amount() == null || draft.paymentDate() == null) return askForMissingDetails(record, draft);
            return resolveDebitAccountAndPrepareConfirmation(record, draft);
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

        // Auto-select only when there is exactly one payee with exactly one selectable
        // (Local) account. Anything more ambiguous (multiple payees, or multiple accounts on
        // a single payee) must go through the explicit selection card below.
        if (matches.size() == 1) {
            RegisteredPayee onlyPayee = matches.get(0);
            List<RegisteredAccount> selectable = selectableAccounts(onlyPayee);
            if (selectable.isEmpty()) {
                return noSelectableAccountsFound(record, onlyPayee);
            }
            if (selectable.size() == 1) {
                draft = stateMachine.updateDraft(draft, draft.payeeQueryText(),
                        PayeeStore.toSummary(onlyPayee, selectable.get(0)),
                        draft.amount(), draft.paymentDate(), draft.status(), null);
                record.setSession(stateMachine.withDraft(record.session(), draft));
                if (draft.amount() == null || draft.paymentDate() == null) return askForMissingDetails(record, draft);
                return resolveDebitAccountAndPrepareConfirmation(record, draft);
            }
        }

        return askToChoosePayeeAccount(record, draft, matches);
    }

    public ChatMessage selectPayee(SessionRecord record, String selectedAddressId) {
        if (selectedAddressId == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Invalid selection", "No item was selected.")));
        }
        PayeeAccountRef ref = payees.findAccount(record.profileId(), selectedAddressId).orElse(null);
        PaymentDraft draft = record.session().activeDraft();
        if (ref == null || draft == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Selection expired", "The selected payee account is no longer available.")));
        }
        if (ref.account().isInternational()) {
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("Cross-border payment not supported",
                            "The selected account is for cross-border / international payment, which "
                                    + "is not supported in this POC. Please pick a Local account.")
            ));
        }

        PayeeSummary summary = PayeeStore.toSummary(ref.payee(), ref.account());
        draft = stateMachine.updateDraft(draft, draft.payeeQueryText(), summary,
                draft.amount(),
                draft.paymentDate(),
                draft.status(), null);
        record.setSession(stateMachine.withDraft(record.session(), draft));
        if (draft.amount() == null || draft.paymentDate() == null) return askForMissingDetails(record, draft);
        return resolveDebitAccountAndPrepareConfirmation(record, draft);
    }

    public ChatMessage selectDebitAccount(SessionRecord record, String selectedAccountId) {
        if (selectedAccountId == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Invalid selection", "No debit account was selected.")));
        }
        PaymentDraft draft = record.session().activeDraft();
        DebitAccountSummary selected = debitAccounts.findSelectableAccount(record.profileId(), selectedAccountId)
                .map(this::toSummary)
                .orElse(null);
        if (draft == null || selected == null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("Selection expired", "The selected debit account is no longer available.")));
        }
        draft = stateMachine.updateSelectedDebitAccount(draft, selected);
        record.setSession(stateMachine.withDraft(record.session(), draft));
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
                    draft.selectedDebitAccount(),
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
        if (draft.selectedPayee() != null && draft.selectedDebitAccount() == null) {
            prompt = prompt + " Once those are set, I will show the eligible debit accounts for you to choose from.";
        }

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
                        "Please confirm the payee, debit account, amount, and payment date before I submit the domestic payment."),
                summaryBlock("Domestic payment summary", draftFields(draft), metadata)
        ));
    }

    private ChatMessage askToChoosePayeeAccount(SessionRecord record, PaymentDraft draft,
                                                List<RegisteredPayee> matches) {
        stateMachine.transition(record, ConversationState.AWAITING_PAYEE_SELECTION, ChatSessionStatus.ACTIVE);
        stateMachine.titleFromDraft(record, draft);

        // Build a flat selectable-item list (every account is a leaf the FE can SELECT_ITEM on)
        // plus a hierarchical `payees` metadata so the FE can render two-level cards with
        // pagination at both levels.
        List<ContentBlock.SelectableItem> items = new ArrayList<>();
        for (RegisteredPayee payee : matches) {
            for (RegisteredAccount account : payee.accounts()) {
                items.add(new ContentBlock.SelectableItem(
                        account.addressId(),
                        payee.displayName(),
                        accountDescriptor(account),
                        null,
                        accountSelectionMetadata(payee, account)
                ));
            }
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("purpose", "payee-account-selection");
        metadata.put("payeeCount", matches.size());
        metadata.put("payees", matches.stream()
                .map(this::payeeHierarchyMetadata)
                .toList());
        metadata.put("payeePageSize", 10);
        metadata.put("accountPageSize", 10);

        String headline = matches.size() == 1
                ? "I found one registered payee for \"" + draft.payeeQueryText()
                        + "\" with multiple accounts. Please choose the account to pay."
                : "I found " + matches.size() + " registered payees for \""
                        + draft.payeeQueryText() + "\". Please choose the payee and account.";

        return assistantMessage(record.session().sessionId(), List.of(
                textBlock("Choose payee", headline),
                blocks.selectableListBlock("Registered payee matches", items, metadata)
        ));
    }

    private ChatMessage resolveDebitAccountAndPrepareConfirmation(SessionRecord record, PaymentDraft draft) {
        List<AccountGroup> groups = debitAccounts.loadAccounts(record.profileId());
        List<SelectableAccount> accounts = selectableAccounts(groups);
        if (accounts.isEmpty()) {
            stateMachine.transition(record, ConversationState.COLLECTING_DETAILS, ChatSessionStatus.ACTIVE);
            return assistantMessage(record.session().sessionId(), List.of(
                    errorBlock("No debit accounts available",
                            "I could not find any debit accounts to fund this payment.")
            ));
        }
        if (draft.selectedDebitAccount() != null) {
            String selectedAccountId = draft.selectedDebitAccount().accountId();
            boolean stillAvailable = accounts.stream()
                    .anyMatch(account -> Objects.equals(account.accountId(), selectedAccountId));
            if (stillAvailable) return prepareConfirmation(record, draft);
            draft = stateMachine.updateSelectedDebitAccount(draft, null);
            record.setSession(stateMachine.withDraft(record.session(), draft));
        }
        if (accounts.size() == 1) {
            draft = stateMachine.updateSelectedDebitAccount(draft, toSummary(accounts.get(0)));
            record.setSession(stateMachine.withDraft(record.session(), draft));
            return prepareConfirmation(record, draft);
        }
        return askToChooseDebitAccount(record, draft, groups, accounts.size());
    }

    private ChatMessage askToChooseDebitAccount(SessionRecord record, PaymentDraft draft,
                                                List<AccountGroup> groups,
                                                int accountCount) {
        stateMachine.transition(record, ConversationState.AWAITING_DEBIT_ACCOUNT_SELECTION, ChatSessionStatus.ACTIVE);
        stateMachine.titleFromDraft(record, draft);
        List<ContentBlock.SelectableItem> items = selectableAccounts(groups).stream()
                .map(account -> new ContentBlock.SelectableItem(
                        account.accountId(),
                        firstNonBlank(account.displayLabel(), account.accountDisplay()),
                        accountDescriptor(account),
                        null,
                        debitSubAccountMetadata(account)
                ))
                .toList();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("purpose", "debit-account-selection");
        metadata.put("groupCount", groups.size());
        metadata.put("accountCount", accountCount);
        metadata.put("groups", groups.stream().map(this::debitAccountGroupMetadata).toList());
        metadata.put("groupPageSize", 10);
        metadata.put("subAccountPageSize", 10);

        return assistantMessage(record.session().sessionId(), List.of(
                textBlock("Choose debit account",
                        "Please choose the debit account to fund this domestic payment."),
                blocks.selectableListBlock("Available debit accounts", items, metadata)
        ));
    }

    private ChatMessage noSelectableAccountsFound(SessionRecord record, RegisteredPayee payee) {
        stateMachine.transition(record, ConversationState.COLLECTING_DETAILS, ChatSessionStatus.ACTIVE);
        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("No domestic accounts available",
                        "I found \"" + payee.displayName() + "\" but none of the linked accounts are "
                                + "available for domestic payment. Cross-border accounts are not "
                                + "supported in this POC.")
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

        int totalAccounts = matches.stream().mapToInt(p -> p.accounts().size()).sum();
        String headline = query != null
                ? "I found " + matches.size() + " registered payee" + (matches.size() == 1 ? "" : "s")
                        + " (" + totalAccounts + " account" + (totalAccounts == 1 ? "" : "s")
                        + ") matching \"" + query + "\"."
                : "I found " + matches.size() + " registered payee" + (matches.size() == 1 ? "" : "s")
                        + " with " + totalAccounts + " account" + (totalAccounts == 1 ? "" : "s")
                        + " in total.";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("purpose", "registered-payee-results");
        metadata.put("payeeCount", matches.size());
        metadata.put("accountCount", totalAccounts);
        metadata.put("payees", matches.stream().map(this::payeeHierarchyMetadata).toList());
        metadata.put("payeePageSize", 10);
        metadata.put("accountPageSize", 10);

        return new PayeeLookupView(matches, List.of(
                textBlock("Registered payees", headline),
                summaryBlock(matches.size() == 1 ? "Registered payee" : "Registered payee results",
                        // The fields list is intentionally empty — the FE renders the
                        // hierarchical UI from metadata.payees. We keep an empty list so the
                        // existing SUMMARY_CARD shape stays valid.
                        List.of(),
                        metadata)
        ));
    }

    private List<RegisteredAccount> selectableAccounts(RegisteredPayee payee) {
        return payee.accounts().stream().filter(a -> !a.isInternational()).toList();
    }

    private String accountDescriptor(RegisteredAccount account) {
        StringBuilder b = new StringBuilder();
        if (account.bankName() != null && !account.bankName().isBlank()) {
            b.append(account.bankName()).append(" • ");
        }
        b.append(account.displayLabel());
        return b.toString();
    }

    private Map<String, Object> payeeHierarchyMetadata(RegisteredPayee payee) {
        Map<String, Object> out = new HashMap<>();
        putIfPresent(out, "contactId", payee.contactId());
        putIfPresent(out, "nickName", payee.nickName());
        putIfPresent(out, "contactFullName", payee.contactFullName());
        out.put("accountCount", payee.accounts().size());
        out.put("accounts", payee.accounts().stream().map(this::accountMetadata).toList());
        return out;
    }

    private Map<String, Object> accountMetadata(RegisteredAccount account) {
        Map<String, Object> out = new HashMap<>();
        putIfPresent(out, "addressId", account.addressId());
        putIfPresent(out, "bankCode", account.bankCode());
        putIfPresent(out, "bankName", account.bankName());
        putIfPresent(out, "accountNumber", account.accountNumber());
        putIfPresent(out, "accountProductType", account.accountProductType());
        putIfPresent(out, "accountProductCode", account.accountProductCode());
        putIfPresent(out, "payeeAccountLabel", account.payeeAccountLabel());
        if (account.accountLimit() != null) {
            out.put("accountLimit", account.accountLimit().toPlainString());
        }
        putIfPresent(out, "accountLimitCurrency", account.accountLimitCurrency());
        putIfPresent(out, "remittanceCurrencyCode", account.remittanceCurrencyCode());
        putIfPresent(out, "effectiveRemittanceCurrency", account.effectiveRemittanceCurrency());
        out.put("selectable", !account.isInternational());
        return out;
    }

    private Map<String, Object> accountSelectionMetadata(RegisteredPayee payee, RegisteredAccount account) {
        Map<String, Object> out = accountMetadata(account);
        putIfPresent(out, "payeeNickName", payee.nickName());
        putIfPresent(out, "payeeContactFullName", payee.contactFullName());
        return out;
    }

    private Map<String, Object> accountToToolMap(RegisteredAccount account) {
        Map<String, Object> out = new HashMap<>();
        out.put("address_id", nullSafe(account.addressId()));
        out.put("bank_code", nullSafe(account.bankCode()));
        out.put("bank_name", nullSafe(account.bankName()));
        out.put("account_number", nullSafe(account.accountNumber()));
        out.put("account_product_type", nullSafe(account.accountProductType()));
        out.put("payee_account_label", nullSafe(account.payeeAccountLabel()));
        out.put("remittance_currency", nullSafe(account.effectiveRemittanceCurrency()));
        if (account.accountLimit() != null) {
            out.put("account_limit", account.accountLimit().toPlainString());
        }
        out.put("account_limit_currency", nullSafe(account.accountLimitCurrency()));
        return out;
    }

    private List<ContentBlock> buildDebitAccountLookupBlocks(List<AccountGroup> groups) {
        List<SelectableAccount> accounts = selectableAccounts(groups);
        if (accounts.isEmpty()) {
            return List.of(infoBlock("No debit accounts available",
                    "I could not find any debit accounts for this profile."));
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("purpose", "debit-account-results");
        metadata.put("groupCount", groups.size());
        metadata.put("accountCount", accounts.size());
        metadata.put("groups", groups.stream().map(this::debitAccountGroupMetadata).toList());
        metadata.put("groupPageSize", 10);
        metadata.put("subAccountPageSize", 10);
        String headline = "I found " + accounts.size() + " debit account"
                + (accounts.size() == 1 ? "" : "s") + " across " + groups.size()
                + " account group" + (groups.size() == 1 ? "" : "s")
                + " you can use for domestic payments.";
        return List.of(
                textBlock("My debit accounts", headline),
                summaryBlock("Available debit accounts", List.of(), metadata)
        );
    }

    private Map<String, Object> debitAccountGroupMetadata(AccountGroup group) {
        Map<String, Object> out = new HashMap<>();
        out.put("groupId", group.groupId());
        ParentAccount parent = group.parentAccount();
        if (parent != null) {
            putIfPresent(out, "parentAccountId", parent.accountId());
            putIfPresent(out, "accountDisplay", parent.accountDisplay());
            putIfPresent(out, "productDescription", parent.productDescription());
        }
        out.put("subAccountCount", group.subAccounts().size());
        out.put("subAccounts", group.subAccounts().stream().map(this::debitSubAccountMetadata).toList());
        return out;
    }

    private Map<String, Object> debitSubAccountMetadata(SelectableAccount account) {
        Map<String, Object> out = new HashMap<>();
        putIfPresent(out, "accountId", account.accountId());
        putIfPresent(out, "parentAccountId", account.parentAccountId());
        putIfPresent(out, "accountDisplay", account.accountDisplay());
        putIfPresent(out, "productCategoryCode", account.productCategoryCode());
        putIfPresent(out, "productDescription", account.productDescription());
        putIfPresent(out, "displayLabel", account.displayLabel());
        putIfPresent(out, "currency", account.currency());
        putIfPresent(out, "ledgerBalanceIndicator", account.ledgerBalanceIndicator());
        if (account.ledgerBalanceAmount() != null) {
            out.put("ledgerBalanceAmount", account.ledgerBalanceAmount().toPlainString());
        }
        putIfPresent(out, "ledgerBalanceCurrency", account.ledgerBalanceCurrency());
        return out;
    }

    private Map<String, Object> debitAccountGroupToolMap(AccountGroup group) {
        Map<String, Object> out = new HashMap<>();
        ParentAccount parent = group.parentAccount();
        out.put("group_id", group.groupId());
        if (parent != null) {
            out.put("account_display", nullSafe(parent.accountDisplay()));
            out.put("product_description", nullSafe(parent.productDescription()));
        }
        out.put("sub_account_count", group.subAccounts().size());
        out.put("sub_accounts", group.subAccounts().stream().map(this::debitSubAccountToolMap).toList());
        return out;
    }

    private Map<String, Object> debitSubAccountToolMap(SelectableAccount account) {
        Map<String, Object> out = new HashMap<>();
        out.put("account_id", nullSafe(account.accountId()));
        out.put("account_display", nullSafe(account.accountDisplay()));
        out.put("product_category_code", nullSafe(account.productCategoryCode()));
        out.put("product_description", nullSafe(account.productDescription()));
        out.put("display_label", nullSafe(account.displayLabel()));
        out.put("currency", nullSafe(account.currency()));
        out.put("ledger_balance_indicator", nullSafe(account.ledgerBalanceIndicator()));
        if (account.ledgerBalanceAmount() != null) {
            out.put("ledger_balance_amount", account.ledgerBalanceAmount().toPlainString());
        }
        out.put("ledger_balance_currency", nullSafe(account.ledgerBalanceCurrency()));
        return out;
    }

    private DebitAccountSummary toSummary(SelectableAccount account) {
        return new DebitAccountSummary(
                account.accountId(),
                account.accountDisplay(),
                account.productCategoryCode(),
                account.productDescription(),
                firstNonBlank(account.displayLabel(), account.productDescription(), account.accountDisplay()),
                account.currency()
        );
    }

    private List<SelectableAccount> selectableAccounts(List<AccountGroup> groups) {
        return groups.stream()
                .flatMap(group -> group.subAccounts().stream())
                .toList();
    }

    private String accountDescriptor(SelectableAccount account) {
        StringBuilder builder = new StringBuilder();
        if (account.productDescription() != null && !account.productDescription().isBlank()) {
            builder.append(account.productDescription().trim());
        }
        String balanceDisplay = balanceDescriptor(account);
        if (balanceDisplay != null) {
            if (builder.length() > 0) builder.append(" • ");
            builder.append(balanceDisplay);
        }
        return builder.length() == 0 ? account.accountDisplay() : builder.toString();
    }

    private String balanceDescriptor(SelectableAccount account) {
        String indicator = account.ledgerBalanceIndicator();
        if (indicator == null || indicator.isBlank()) return null;
        if ("BALANCE_AVAILABLE".equalsIgnoreCase(indicator)) {
            if (account.ledgerBalanceAmount() == null) return indicator;
            String currency = firstNonBlank(account.ledgerBalanceCurrency(), account.currency());
            return currency == null
                    ? account.ledgerBalanceAmount().toPlainString()
                    : currency + " " + account.ledgerBalanceAmount().toPlainString();
        }
        return indicator;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
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
