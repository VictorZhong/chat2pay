package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.ConversationSession;
import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.Profile;
import java.util.List;

public record JourneyAgentContext(
        Profile profile,
        ConversationSession session,
        PaymentDraft draft,
        JourneyUserSignal userSignal,
        List<JourneyToolDefinition> availableTools,
        List<JourneyToolResult> toolResults,
        int iteration) {

    public JourneyToolResult latestToolResult() {
        return toolResults.isEmpty() ? null : toolResults.getLast();
    }
}
