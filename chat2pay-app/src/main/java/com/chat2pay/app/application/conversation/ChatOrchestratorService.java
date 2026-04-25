package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.ChatTurnResponse;
import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.api.dto.ChatDtos.SendMessageRequest;
import com.chat2pay.app.api.dto.ChatDtos.UiEventRequest;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.api.dto.ContentBlock.DisplayField;
import com.chat2pay.app.api.dto.ContentBlock.SelectableItem;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.MessageKind;
import com.chat2pay.app.domain.conversation.MessageRole;
import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.domain.payment.PaymentType;
import com.chat2pay.app.persistence.memory.InMemoryPayeeStore;
import com.chat2pay.app.persistence.memory.InMemoryPayeeStore.RegisteredPayee;
import com.chat2pay.app.persistence.memory.InMemorySessionStore;
import com.chat2pay.app.persistence.memory.InMemorySessionStore.SessionRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatOrchestratorService {

    private static final String DEFAULT_CURRENCY = "HKD";

    private static final Pattern AMOUNT = Pattern.compile("(?:hkd\\s*)?(\\d+(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYMENT_INTENT = Pattern.compile("\\b(pay|send|transfer)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOKUP_INTENT = Pattern.compile("\\b(payee|payees|registered|lookup|look up|find|show|list)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERNATIONAL_INTENT = Pattern.compile("\\b(international|overseas|swift|wire)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DO_I_HAVE = Pattern.compile("do i have", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSITIVE_CONFIRM = Pattern.compile("\\b(confirm|confirmed|yes|okay|ok|go ahead|proceed|send it)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANCEL = Pattern.compile("\\b(cancel|stop|never mind|don'?t|do not)\\b", Pattern.CASE_INSENSITIVE);

    private static final List<Pattern> PAYEE_QUERY_PATTERNS = List.of(
            Pattern.compile("(?:pay|send|transfer)(?:\\s+to)?\\s+(.+?)(?=\\s+\\d|\\s+hkd|\\s+today|\\s+tomorrow|\\s+later|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:find|lookup|look up|check|show|list)(?:\\s+my)?(?:\\s+registered)?(?:\\s+payees?|\\s+payee)?(?:\\s+for)?\\s+(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("do i have\\s+(.+?)\\s+(?:registered|as a payee)", Pattern.CASE_INSENSITIVE)
    );

    private final InMemorySessionStore sessions;
    private final InMemoryPayeeStore payees;

    public ChatOrchestratorService(InMemorySessionStore sessions, InMemoryPayeeStore payees) {
        this.sessions = sessions;
        this.payees = payees;
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
        ConversationState state = record.session().state();

        if (state == ConversationState.AWAITING_CONFIRMATION) {
            if (POSITIVE_CONFIRM.matcher(text).find()) return executePayment(record);
            if (CANCEL.matcher(text).find()) return cancelPayment(record);
        }

        if (state == ConversationState.AWAITING_PAYEE_SELECTION && record.session().activeDraft() != null) {
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("Select a payee",
                            "Please choose one of the registered payees from the selection list before I continue.")
            ));
        }

        if (INTERNATIONAL_INTENT.matcher(text).find()) {
            transition(record, ConversationState.IDLE, ChatSessionStatus.ACTIVE);
            return assistantMessage(record.session().sessionId(), List.of(
                    infoBlock("Not supported in V1",
                            "This POC currently supports registered payee lookup and domestic payment to a registered payee only.")
            ));
        }

        if (PAYMENT_INTENT.matcher(text).find()) return continueDomesticPayment(record, text);
        if (LOOKUP_INTENT.matcher(text).find() || DO_I_HAVE.matcher(text).find()) {
            return handlePayeeLookup(record, text);
        }

        return assistantMessage(record.session().sessionId(), List.of(
                infoBlock("Try a supported request",
                        "Ask me to find a registered payee, or tell me who to pay, how much, and whether it should go today or tomorrow.")
        ));
    }

    private ChatMessage handlePayeeLookup(SessionRecord record, String text) {
        String query = extractPayeeQuery(text);
        List<RegisteredPayee> matches = query != null ? payees.findByQuery(query) : payees.all();

        transition(record, ConversationState.IDLE, ChatSessionStatus.ACTIVE);
        if (query != null) setTitle(record, "Find " + titleCase(query));
        else setTitle(record, "Registered payees");

        if (matches.isEmpty()) {
            return assistantMessage(record.session().sessionId(), List.of(
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

        return assistantMessage(record.session().sessionId(), List.of(
                textBlock("Registered payees", headline),
                summaryBlock(matches.size() == 1 ? "Registered payee" : "Registered payee results", fields, null)
        ));
    }

    private ChatMessage continueDomesticPayment(SessionRecord record, String text) {
        PaymentDraft draft = ensureDraft(record);

        String payeeQuery = extractPayeeQuery(text);
        Double amount = extractAmount(text);
        LocalDate date = extractPaymentDate(text);

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
        return continueDomesticPayment(record, "");
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

        String reference = "DOM-" + LocalDate.now().toString().replace("-", "") + "-"
                + draft.draftId().substring(Math.max(0, draft.draftId().length() - 4));
        draft = updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), draft.amount(),
                draft.paymentDate(), PaymentDraftStatus.CONFIRMED, reference);
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
        return new PaymentDraft(d.draftId(), d.sessionId(), d.paymentType(), status,
                payeeQuery, selected, amount, d.currency() != null ? d.currency() : DEFAULT_CURRENCY,
                date, reference, null, d.context(), Instant.now());
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

    // ---- parsing --------------------------------------------------------------

    private Double extractAmount(String text) {
        Matcher m = AMOUNT.matcher(text.replace(",", ""));
        if (!m.find()) return null;
        try {
            double v = Double.parseDouble(m.group(1));
            return v > 0 ? v : null;
        } catch (NumberFormatException e) { return null; }
    }

    private LocalDate extractPaymentDate(String text) {
        String n = text.toLowerCase(Locale.ROOT);
        if (n.contains("tomorrow") || n.contains("later")) return LocalDate.now().plusDays(1);
        if (n.contains("today") || n.contains("now")) return LocalDate.now();
        return null;
    }

    private String extractPayeeQuery(String text) {
        String alias = payees.findAliasInText(text);
        if (alias != null) return alias;
        for (Pattern p : PAYEE_QUERY_PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find() && m.group(1) != null) {
                String sanitized = sanitize(m.group(1));
                if (!sanitized.isBlank()) return sanitized;
            }
        }
        return null;
    }

    private String sanitize(String raw) {
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\b(hkd|today|tomorrow|now|later|please|thanks|registered|payee|payees|accounts?|my)\\b", " ")
                .replaceAll("\\s+", " ").trim();
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
