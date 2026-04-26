package com.chat2pay.app.application.capability;

import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.integration.llm.LlmCompletionRequest.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record CapabilityDefinition(
        CapabilityId id,
        String toolName,
        List<String> legacyToolNames,
        String description,
        Map<String, Object> inputProperties,
        List<String> requiredInputs,
        String outputType,
        CapabilityRiskLevel riskLevel,
        ConfirmationPolicy confirmationPolicy,
        Set<ConversationState> allowedStates,
        boolean llmVisible
) {
    public CapabilityDefinition {
        legacyToolNames = legacyToolNames == null ? List.of() : List.copyOf(legacyToolNames);
        inputProperties = inputProperties == null ? Map.of() : Map.copyOf(inputProperties);
        requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
        allowedStates = allowedStates == null ? Set.of() : Set.copyOf(allowedStates);
    }

    public ToolDefinition toToolDefinition() {
        return new ToolDefinition(toolName, description, Map.of(
                "type", "object",
                "properties", inputProperties,
                "required", requiredInputs,
                "additionalProperties", false
        ));
    }
}
