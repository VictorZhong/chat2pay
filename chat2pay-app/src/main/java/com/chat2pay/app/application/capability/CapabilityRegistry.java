package com.chat2pay.app.application.capability;

import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.integration.llm.LlmCompletionRequest.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class CapabilityRegistry {

    private final Map<CapabilityId, CapabilityDefinition> byId;
    private final Map<String, CapabilityDefinition> byToolName;

    public CapabilityRegistry() {
        this(defaultDefinitions());
    }

    CapabilityRegistry(List<CapabilityDefinition> definitions) {
        Map<CapabilityId, CapabilityDefinition> ids = new EnumMap<>(CapabilityId.class);
        Map<String, CapabilityDefinition> tools = new LinkedHashMap<>();
        for (CapabilityDefinition definition : definitions) {
            ids.put(definition.id(), definition);
            tools.put(definition.toolName(), definition);
            for (String legacyName : definition.legacyToolNames()) {
                tools.put(legacyName, definition);
            }
        }
        this.byId = Map.copyOf(ids);
        this.byToolName = Map.copyOf(tools);
    }

    public List<CapabilityDefinition> all() {
        return List.copyOf(byId.values());
    }

    public Optional<CapabilityDefinition> findById(CapabilityId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<CapabilityDefinition> findByToolName(String toolName) {
        return Optional.ofNullable(byToolName.get(toolName));
    }

    public List<ToolDefinition> llmToolDefinitions() {
        return byId.values().stream()
                .filter(CapabilityDefinition::llmVisible)
                .map(CapabilityDefinition::toToolDefinition)
                .toList();
    }

    public static List<ToolDefinition> defaultLlmToolDefinitions() {
        return defaultDefinitions().stream()
                .filter(CapabilityDefinition::llmVisible)
                .map(CapabilityDefinition::toToolDefinition)
                .toList();
    }

    public static List<CapabilityDefinition> defaultDefinitions() {
        return List.of(
                new CapabilityDefinition(
                        CapabilityId.LIST_PAYEES,
                        "get_registered_payees",
                        List.of(),
                        "Fetch registered domestic payees. Use when the user asks to find, list, or check payees.",
                        Map.of(
                                "name_query", Map.of(
                                        "type", "string",
                                        "description", "Optional payee-name search string from the user request."
                                )
                        ),
                        List.of(),
                        "payeeList",
                        CapabilityRiskLevel.READ_ONLY,
                        ConfirmationPolicy.NONE,
                        Set.of(ConversationState.IDLE, ConversationState.COLLECTING_DETAILS,
                                ConversationState.AWAITING_PAYEE_SELECTION),
                        true
                ),
                new CapabilityDefinition(
                        CapabilityId.PREPARE_DOMESTIC_PAYMENT,
                        "prepare_domestic_payment",
                        List.of(),
                        "Collect or update domestic payment details before explicit confirmation.",
                        Map.of(
                                "payeeQuery", Map.of(
                                        "type", "string",
                                        "description", "User-facing payee name or alias. Do not pass opaque ids."
                                ),
                                "amount", Map.of(
                                        "type", "number",
                                        "description", "Positive payment amount in HKD."
                                ),
                                "paymentDate", Map.of(
                                        "type", "string",
                                        "format", "date",
                                        "description", "Payment date as YYYY-MM-DD."
                                )
                        ),
                        List.of(),
                        "paymentDraft",
                        CapabilityRiskLevel.DRAFT_MUTATION,
                        ConfirmationPolicy.NONE,
                        Set.of(ConversationState.IDLE, ConversationState.COLLECTING_DETAILS,
                                ConversationState.AWAITING_PAYEE_SELECTION,
                                ConversationState.AWAITING_CONFIRMATION),
                        true
                ),
                new CapabilityDefinition(
                        CapabilityId.CONFIRM_DOMESTIC_PAYMENT,
                        "confirm_domestic_payment",
                        List.of(),
                        "Directly confirm the active V1 domestic payment draft. Use only after explicit user confirmation.",
                        Map.of(),
                        List.of(),
                        "paymentConfirmation",
                        CapabilityRiskLevel.SIDE_EFFECTING,
                        ConfirmationPolicy.EXPLICIT_USER_CONFIRMATION,
                        Set.of(ConversationState.AWAITING_CONFIRMATION),
                        true
                ),
                new CapabilityDefinition(
                        CapabilityId.CANCEL_PAYMENT,
                        "cancel_payment",
                        List.of(),
                        "Cancel the active payment draft.",
                        Map.of(),
                        List.of(),
                        "paymentCancellation",
                        CapabilityRiskLevel.DRAFT_MUTATION,
                        ConfirmationPolicy.NONE,
                        Set.of(ConversationState.COLLECTING_DETAILS, ConversationState.AWAITING_PAYEE_SELECTION,
                                ConversationState.AWAITING_CONFIRMATION),
                        true
                ),
                new CapabilityDefinition(
                        CapabilityId.UNSUPPORTED_CROSS_BORDER_PAYMENT,
                        "unsupported_cross_border_payment",
                        List.of("unsupported_international_payment"),
                        "Use for cross-border, overseas, international, SWIFT, or wire transfer requests.",
                        Map.of(),
                        List.of(),
                        "unsupportedCapability",
                        CapabilityRiskLevel.UNSUPPORTED,
                        ConfirmationPolicy.UNSUPPORTED,
                        Set.of(),
                        true
                )
        );
    }
}
