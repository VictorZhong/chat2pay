package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.DebitAccountSummary;
import com.chat2pay.app.api.dto.ChatDtos.ErrorSummary;
import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.domain.payment.PaymentType;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Component
public class ConversationStateMachine {

    private static final String DEFAULT_CURRENCY = "HKD";

    public PaymentDraft ensureDraft(SessionRecord record, String currency) {
        PaymentDraft existing = record.session().activeDraft();
        if (existing != null) return existing;
        PaymentDraft draft = new PaymentDraft(
                "draft_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                record.session().sessionId(), PaymentType.DOMESTIC_PAYMENT, PaymentDraftStatus.DRAFT,
                null, null, null, null, resolvedCurrency(currency), null, null, null, null, Instant.now()
        );
        record.setSession(withDraft(record.session(), draft));
        return draft;
    }

    public PaymentDraft updateDraft(PaymentDraft d, String payeeQuery, PayeeSummary selected,
                                    BigDecimal amount, LocalDate date, PaymentDraftStatus status,
                                    String reference) {
        return updateDraft(d, payeeQuery, selected, amount, date, status, reference, null);
    }

    public PaymentDraft updateDraft(PaymentDraft d, String payeeQuery, PayeeSummary selected,
                                    BigDecimal amount, LocalDate date, PaymentDraftStatus status,
                                    String reference, ErrorSummary lastError) {
        return updateDraft(d, payeeQuery, selected, amount, date, status, reference, lastError, d.context());
    }

    public PaymentDraft updateDraft(PaymentDraft d, String payeeQuery, PayeeSummary selected,
                                    BigDecimal amount, LocalDate date, PaymentDraftStatus status,
                                    String reference, ErrorSummary lastError,
                                    Map<String, Object> context) {
        return updateDraft(d, payeeQuery, selected, d.selectedDebitAccount(), amount, date, status, reference,
                lastError, context);
    }

    public PaymentDraft updateDraft(PaymentDraft d, String payeeQuery, PayeeSummary selected,
                                    DebitAccountSummary selectedDebitAccount,
                                    BigDecimal amount, LocalDate date, PaymentDraftStatus status,
                                    String reference, ErrorSummary lastError,
                                    Map<String, Object> context) {
        BigDecimal scaled = amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
        return new PaymentDraft(d.draftId(), d.sessionId(), d.paymentType(), status,
                payeeQuery, selected, selectedDebitAccount, scaled, resolvedCurrency(d.currency()),
                date, reference, lastError, context, Instant.now());
    }

    public PaymentDraft updateSelectedDebitAccount(PaymentDraft draft, DebitAccountSummary selectedDebitAccount) {
        return updateDraft(draft, draft.payeeQueryText(), draft.selectedPayee(), selectedDebitAccount,
                draft.amount(), draft.paymentDate(), draft.status(), draft.downstreamReference(),
                draft.lastError(), draft.context());
    }

    public ChatSessionDetail withDraft(ChatSessionDetail session, PaymentDraft draft) {
        return new ChatSessionDetail(session.sessionId(), session.title(), session.titleLocked(),
                session.status(), session.state(), session.llmProvider(), draft,
                session.createdAt(), Instant.now());
    }

    public void transition(SessionRecord record, ConversationState state, ChatSessionStatus status) {
        ChatSessionDetail session = record.session();
        record.setSession(new ChatSessionDetail(session.sessionId(), session.title(), session.titleLocked(),
                status, state, session.llmProvider(), session.activeDraft(),
                session.createdAt(), Instant.now()));
    }

    public void setTitle(SessionRecord record, String title) {
        ChatSessionDetail session = record.session();
        if (session.titleLocked()) return;
        String trimmed = title.length() > 120 ? title.substring(0, 120) : title;
        record.setSession(new ChatSessionDetail(session.sessionId(), trimmed, session.titleLocked(),
                session.status(), session.state(), session.llmProvider(), session.activeDraft(),
                session.createdAt(), Instant.now()));
    }

    public void titleFromDraft(SessionRecord record, PaymentDraft draft) {
        if (draft.selectedPayee() != null) setTitle(record, "Pay " + draft.selectedPayee().name());
        else if (draft.payeeQueryText() != null) setTitle(record, "Pay " + titleCase(draft.payeeQueryText()));
    }

    public void touch(SessionRecord record) {
        ChatSessionDetail session = record.session();
        record.setSession(new ChatSessionDetail(session.sessionId(), session.title(), session.titleLocked(),
                session.status(), session.state(), session.llmProvider(), session.activeDraft(),
                session.createdAt(), Instant.now()));
    }

    public String titleCase(String s) {
        String[] parts = s.split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return b.toString();
    }

    private static String resolvedCurrency(String currency) {
        return currency != null ? currency : DEFAULT_CURRENCY;
    }
}
