package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.WorkflowState;
import com.chat2pay.app.integration.downstream.RegisteredPayee;
import com.chat2pay.app.integration.downstream.RegisteredPayeeDirectoryClient;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ListRegisteredPayeesToolHandler implements JourneyToolHandler {

    private final RegisteredPayeeDirectoryClient payeeDirectoryClient;
    private final PayeeMatcher payeeMatcher;

    public ListRegisteredPayeesToolHandler(
            RegisteredPayeeDirectoryClient payeeDirectoryClient,
            PayeeMatcher payeeMatcher) {
        this.payeeDirectoryClient = payeeDirectoryClient;
        this.payeeMatcher = payeeMatcher;
    }

    @Override
    public JourneyToolDefinition definition() {
        return new JourneyToolDefinition(
                HeuristicJourneyAgentPlanner.TOOL_LIST_REGISTERED_PAYEES,
                "Fetch the current user's registered payees and resolve the requested payee name against them.",
                "Use only when draft.payeeNameInput exists and draft.payeeIdIndex is not resolved.");
    }

    @Override
    public JourneyToolResult execute(JourneyToolExecutionContext context) {
        PaymentDraft draft = context.draft();
        if (draft == null) {
            return JourneyToolResult.failure(definition().name(), "No active payment draft exists.");
        }
        if (draft.getPayeeNameInput() == null || draft.getPayeeNameInput().isBlank()) {
            return JourneyToolResult.failure(definition().name(), "Payee name is required before payee lookup.");
        }

        try {
            List<RegisteredPayee> registeredPayees = payeeDirectoryClient.listRegisteredPayees(context.profile());
            var matchResult = payeeMatcher.match(draft.getPayeeNameInput(), registeredPayees);

            if (matchResult.isNone()) {
                clearResolvedPayee(draft);
                context.session().setWorkflowState(WorkflowState.COLLECTING_PAYMENT_DETAILS);
                draft.setWorkflowState(WorkflowState.COLLECTING_PAYMENT_DETAILS);
                return JourneyToolResult.success(definition().name(), Map.of(
                        HeuristicJourneyAgentPlanner.MATCH_STATUS_KEY, HeuristicJourneyAgentPlanner.MATCH_NONE,
                        "requestedPayeeName", draft.getPayeeNameInput(),
                        "candidates", List.of()));
            }

            if (matchResult.isAmbiguous()) {
                draft.replaceCandidatePayees(matchResult.matches());
                context.session().setWorkflowState(WorkflowState.RESOLVING_PAYEE);
                draft.setWorkflowState(WorkflowState.RESOLVING_PAYEE);
                return JourneyToolResult.success(definition().name(), Map.of(
                        HeuristicJourneyAgentPlanner.MATCH_STATUS_KEY, HeuristicJourneyAgentPlanner.MATCH_AMBIGUOUS,
                        "requestedPayeeName", draft.getPayeeNameInput(),
                        "candidates", toCandidatePayload(matchResult.matches())));
            }

            applyResolvedPayee(draft, matchResult.uniqueMatch());
            draft.clearCandidatePayees();
            return JourneyToolResult.success(definition().name(), Map.of(
                    HeuristicJourneyAgentPlanner.MATCH_STATUS_KEY, HeuristicJourneyAgentPlanner.MATCH_UNIQUE,
                    "resolvedPayee", Map.of(
                            "payeeIdIndex", draft.getPayeeIdIndex(),
                            "payeeType", draft.getPayeeType(),
                            "payeeDisplay", draft.getPayeeDisplay())));
        } catch (RuntimeException exception) {
            return JourneyToolResult.failure(
                    definition().name(),
                    "Registered payee lookup failed: " + exception.getMessage());
        }
    }

    private void applyResolvedPayee(PaymentDraft draft, RegisteredPayee payee) {
        draft.setPayeeIdIndex(payee.payeeIdIndex());
        draft.setPayeeType(payee.payeeType());
        draft.setPayeeDisplay(payee.name());
        if (draft.getPayeeNameInput() == null || draft.getPayeeNameInput().isBlank()) {
            draft.setPayeeNameInput(payee.name());
        }
    }

    private void clearResolvedPayee(PaymentDraft draft) {
        draft.setPayeeIdIndex(null);
        draft.setPayeeType(null);
        draft.setPayeeDisplay(null);
        draft.clearCandidatePayees();
    }

    private List<Map<String, Object>> toCandidatePayload(List<RegisteredPayee> matches) {
        return matches.stream()
                .map(payee -> Map.<String, Object>of(
                        "payeeIdIndex", payee.payeeIdIndex(),
                        "payeeType", payee.payeeType(),
                        "name", payee.name(),
                        "description", payee.description()))
                .toList();
    }
}
