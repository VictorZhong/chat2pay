package com.chat2pay.app.application.capability;

import com.chat2pay.app.integration.llm.LlmCompletionRequest.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRegistryTests {

    @Test
    void exposesCanonicalLlmToolsAndKeepsLegacyCrossBorderAliasInternal() {
        CapabilityRegistry registry = new CapabilityRegistry();

        List<String> llmToolNames = registry.llmToolDefinitions().stream()
                .map(ToolDefinition::name)
                .toList();

        assertThat(llmToolNames)
                .contains("get_my_debit_accounts",
                        "get_registered_payees",
                        "prepare_domestic_payment",
                        "confirm_domestic_payment",
                        "cancel_payment",
                        "unsupported_cross_border_payment")
                .doesNotContain("unsupported_international_payment");
        assertThat(registry.findByToolName("unsupported_international_payment"))
                .get()
                .extracting(CapabilityDefinition::id)
                .isEqualTo(CapabilityId.UNSUPPORTED_CROSS_BORDER_PAYMENT);
    }

    @Test
    void marksDomesticConfirmAsSideEffectingAndExplicitlyConfirmed() {
        CapabilityRegistry registry = new CapabilityRegistry();

        CapabilityDefinition confirm = registry.findByToolName("confirm_domestic_payment").orElseThrow();

        assertThat(confirm.riskLevel()).isEqualTo(CapabilityRiskLevel.SIDE_EFFECTING);
        assertThat(confirm.confirmationPolicy()).isEqualTo(ConfirmationPolicy.EXPLICIT_USER_CONFIRMATION);
        assertThat(confirm.outputType()).isEqualTo("paymentConfirmation");
    }
}
