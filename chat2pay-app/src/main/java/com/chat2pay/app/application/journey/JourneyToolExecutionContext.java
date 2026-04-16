package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.ConversationSession;
import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.Profile;

public record JourneyToolExecutionContext(
        Profile profile,
        ConversationSession session,
        PaymentDraft draft,
        JourneyUserSignal userSignal) {
}
