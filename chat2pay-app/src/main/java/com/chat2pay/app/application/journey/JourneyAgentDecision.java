package com.chat2pay.app.application.journey;

import java.util.List;

public record JourneyAgentDecision(
        JourneyAction action,
        String assistantMessage,
        String toolName,
        List<String> requiredInputs,
        JourneyDraftUpdate draftUpdate) {

    public JourneyAgentDecision mergeWithFallback(JourneyAgentDecision fallback) {
        if (fallback == null) {
            return this;
        }

        return new JourneyAgentDecision(
                action != null ? action : fallback.action,
                assistantMessage != null && !assistantMessage.isBlank() ? assistantMessage : fallback.assistantMessage,
                toolName != null && !toolName.isBlank() ? toolName : fallback.toolName,
                requiredInputs != null && !requiredInputs.isEmpty() ? requiredInputs : fallback.requiredInputs,
                draftUpdate != null ? draftUpdate.mergePreferNonNull(fallback.draftUpdate) : fallback.draftUpdate);
    }
}
