package com.chat2pay.app.application.journey;

import com.chat2pay.app.integration.downstream.RegisteredPayee;
import com.chat2pay.app.integration.downstream.RegisteredPayeeDirectoryClient;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BrowseRegisteredPayeesToolHandler implements JourneyToolHandler {

    private final RegisteredPayeeDirectoryClient payeeDirectoryClient;

    public BrowseRegisteredPayeesToolHandler(RegisteredPayeeDirectoryClient payeeDirectoryClient) {
        this.payeeDirectoryClient = payeeDirectoryClient;
    }

    @Override
    public JourneyToolDefinition definition() {
        return new JourneyToolDefinition(
                HeuristicJourneyAgentPlanner.TOOL_BROWSE_REGISTERED_PAYEES,
                "Fetch the current user's full registered payee directory for browsing.",
                "Use when the user explicitly wants to see their registered payees. Do not require an active payment draft.",
                false);
    }

    @Override
    public JourneyToolResult execute(JourneyToolExecutionContext context) {
        try {
            List<RegisteredPayee> payees = payeeDirectoryClient.listRegisteredPayees(context.profile());
            return JourneyToolResult.success(definition().name(), Map.of(
                    "payees", payees.stream()
                            .map(payee -> Map.<String, Object>of(
                                    "payeeIdIndex", payee.payeeIdIndex(),
                                    "payeeType", payee.payeeType(),
                                    "name", payee.name(),
                                    "description", payee.description()))
                            .toList()));
        } catch (RuntimeException exception) {
            return JourneyToolResult.failure(
                    definition().name(),
                    "Registered payee lookup failed: " + exception.getMessage());
        }
    }
}
