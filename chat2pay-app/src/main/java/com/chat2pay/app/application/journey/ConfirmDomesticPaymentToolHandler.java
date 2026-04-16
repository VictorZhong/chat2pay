package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.TransferStatus;
import com.chat2pay.app.domain.WorkflowState;
import com.chat2pay.app.integration.downstream.DomesticPaymentClient;
import com.chat2pay.app.integration.downstream.PaymentConfirmationResult;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ConfirmDomesticPaymentToolHandler implements JourneyToolHandler {

    private final DomesticPaymentClient domesticPaymentClient;

    public ConfirmDomesticPaymentToolHandler(DomesticPaymentClient domesticPaymentClient) {
        this.domesticPaymentClient = domesticPaymentClient;
    }

    @Override
    public JourneyToolDefinition definition() {
        return new JourneyToolDefinition(
                HeuristicJourneyAgentPlanner.TOOL_CONFIRM_DOMESTIC_PAYMENT,
                "Submit the domestic payment confirm API for the resolved payee and amount.",
                "Use only after an explicit user confirmation and only when payeeIdIndex and amount are already known.");
    }

    @Override
    public JourneyToolResult execute(JourneyToolExecutionContext context) {
        PaymentDraft draft = context.draft();
        if (draft == null) {
            return JourneyToolResult.failure(definition().name(), "No active payment draft exists.");
        }
        if (!context.userSignal().explicitConfirmationRequested()) {
            return JourneyToolResult.failure(
                    definition().name(),
                    "Explicit user confirmation is required before payment confirmation.");
        }
        if (draft.getPayeeIdIndex() == null || draft.getAmount() == null) {
            return JourneyToolResult.failure(
                    definition().name(),
                    "Payee and amount must be resolved before payment confirmation.");
        }

        context.session().setWorkflowState(WorkflowState.CONFIRMING_PAYMENT);
        draft.setWorkflowState(WorkflowState.CONFIRMING_PAYMENT);
        draft.setStatus(TransferStatus.CONFIRMING);

        try {
            PaymentConfirmationResult result = domesticPaymentClient.confirmDomesticPayment(context.profile(), draft);
            return JourneyToolResult.success(definition().name(), Map.of(
                    HeuristicJourneyAgentPlanner.CONFIRMATION_STATUS_KEY, HeuristicJourneyAgentPlanner.CONFIRMATION_SUCCESS,
                    "transferReference", result.transferReference(),
                    "downstreamPayload", result.downstreamPayload()));
        } catch (RuntimeException exception) {
            return JourneyToolResult.failure(
                    definition().name(),
                    "Confirm payment failed: " + exception.getMessage());
        }
    }
}
