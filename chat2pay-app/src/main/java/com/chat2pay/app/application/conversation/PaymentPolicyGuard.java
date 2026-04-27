package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class PaymentPolicyGuard {

    private static final Pattern EXPLICIT_CONFIRMATION = Pattern.compile(
            "\\b(confirm|confirmed|yes|okay|ok|go ahead|proceed|send it|approve)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXPLICIT_CANCELLATION = Pattern.compile(
            "\\b(cancel|stop|never mind|don'?t|do not)\\b",
            Pattern.CASE_INSENSITIVE);

    public boolean hasExplicitConfirmation(String latestUserText) {
        return latestUserText != null && EXPLICIT_CONFIRMATION.matcher(latestUserText).find();
    }

    public boolean isCancellation(String latestUserText) {
        return latestUserText != null && EXPLICIT_CANCELLATION.matcher(latestUserText).find();
    }

    public PolicyDecision canConfirmDomesticPayment(SessionRecord record, String latestUserText) {
        if (record.session().state() != ConversationState.AWAITING_CONFIRMATION) {
            return PolicyDecision.blocked("confirmation_state_required",
                    "The active domestic payment draft is not ready for confirmation.");
        }
        if (!hasExplicitConfirmation(latestUserText)) {
            return PolicyDecision.blocked("explicit_confirmation_required",
                    "Please review the domestic payment summary and explicitly confirm before I submit it.");
        }
        return requireCompleteDomesticDraft(record.session().activeDraft());
    }

    public PolicyDecision requireCompleteDomesticDraft(PaymentDraft draft) {
        if (draft == null) {
            return PolicyDecision.blocked("draft_required",
                    "The payment draft is incomplete. Please provide the missing details first.");
        }
        if (draft.selectedPayee() == null) {
            return PolicyDecision.blocked("payee_required",
                    "The payment draft needs a selected payee before it can be submitted.");
        }
        if (draft.amount() == null || draft.amount().signum() <= 0) {
            return PolicyDecision.blocked("amount_required",
                    "The payment draft needs a positive amount before it can be submitted.");
        }
        if (draft.paymentDate() == null) {
            return PolicyDecision.blocked("payment_date_required",
                    "The payment draft needs a payment date before it can be submitted.");
        }
        return PolicyDecision.allow();
    }

    public PolicyDecision crossBorderUnavailableInV1() {
        return PolicyDecision.blocked("cross_border_payment_not_supported",
                "This POC currently supports registered payee lookup and domestic payment to a registered payee only.");
    }

    public record PolicyDecision(boolean allowed, String code, String message) {
        public static PolicyDecision allow() {
            return new PolicyDecision(true, "allowed", null);
        }

        public static PolicyDecision blocked(String code, String message) {
            return new PolicyDecision(false, code, message);
        }
    }
}
