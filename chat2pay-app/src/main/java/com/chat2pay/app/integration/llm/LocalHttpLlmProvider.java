package com.chat2pay.app.integration.llm;

import com.chat2pay.app.application.journey.HeuristicJourneyAgentPlanner;
import com.chat2pay.app.application.journey.JourneyAction;
import com.chat2pay.app.application.journey.JourneyAgentContext;
import com.chat2pay.app.application.journey.JourneyAgentDecision;
import com.chat2pay.app.application.journey.JourneyAgentPlanner;
import com.chat2pay.app.application.journey.JourneyDraftUpdate;
import com.chat2pay.app.application.journey.JourneyToolDefinition;
import com.chat2pay.app.config.Chat2PayProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Primary
public class LocalHttpLlmProvider implements JourneyAgentPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalHttpLlmProvider.class);

    private final Chat2PayProperties properties;
    private final HeuristicJourneyAgentPlanner fallbackPlanner;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public LocalHttpLlmProvider(
            Chat2PayProperties properties,
            HeuristicJourneyAgentPlanner fallbackPlanner,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.fallbackPlanner = fallbackPlanner;
        this.restClient = restClientBuilder.baseUrl(properties.getLlm().getBaseUrl()).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public JourneyAgentDecision plan(JourneyAgentContext context) {
        JourneyAgentDecision fallback = fallbackPlanner.plan(context);
        if (!properties.getLlm().isEnabled()) {
            return fallback;
        }

        try {
            LlmChatResponse response = restClient.post()
                    .uri(properties.getLlm().getChatPath())
                    .body(new LlmChatRequest(buildPrompt(context), null, List.of()))
                    .retrieve()
                    .body(LlmChatResponse.class);
            JourneyAgentDecision parsed = parseResponse(response);
            if (parsed == null) {
                return properties.getLlm().isFallbackToHeuristics()
                        ? fallback
                        : new JourneyAgentDecision(
                                JourneyAction.FAIL,
                                "The planning model did not return a usable backend action.",
                                null,
                                List.of(),
                                JourneyDraftUpdate.empty());
            }
            return parsed.mergeWithFallback(fallback);
        } catch (Exception exception) {
            LOGGER.debug("Local LLM request failed, using heuristic fallback", exception);
            return properties.getLlm().isFallbackToHeuristics()
                    ? fallback
                    : new JourneyAgentDecision(
                            JourneyAction.FAIL,
                            "The planning model is unavailable for this request.",
                            null,
                            List.of(),
                            JourneyDraftUpdate.empty());
        }
    }

    private String buildPrompt(JourneyAgentContext context) {
        String availableTools = context.availableTools().stream()
                .map(this::formatToolDefinition)
                .collect(Collectors.joining("\n"));
        String serializedContext;
        try {
            serializedContext = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "profile", Map.of(
                            "username", context.profile().username(),
                            "displayName", context.profile().displayName()),
                    "session", Map.of(
                            "workflowState", context.session().getWorkflowState(),
                            "status", context.session().getStatus(),
                            "journeyType", context.session().getJourneyType()),
                    "draft", context.draft(),
                    "userSignal", context.userSignal(),
                    "availableTools", context.availableTools(),
                    "toolResults", context.toolResults(),
                    "iteration", context.iteration()));
        } catch (Exception exception) {
            serializedContext = "{\"error\":\"Unable to serialize planner context\"}";
        }

        return """
                You are the backend planning agent for a banking POC.
                You do not call APIs directly. You choose the next backend action.

                Supported journey in this session: domestic payment to an existing registered payee only.

                Available actions:
                - ASK_USER
                - CALL_TOOL
                - SHOW_CONFIRMATION
                - COMPLETE
                - FAIL
                - UNSUPPORTED
                - CANCEL

                Available tools:
                %s

                Guardrails:
                - never choose %s unless the latest user signal is an explicit confirmation
                - if information is missing, choose ASK_USER
                - if the last tool result is ambiguous, ask the user to select the payee
                - if the last tool result is unique and the draft is complete, choose SHOW_CONFIRMATION
                - if the last confirm tool result succeeded, choose COMPLETE
                - if the request is outside this POC, choose UNSUPPORTED

                Return JSON only with this exact shape:
                {
                  "action": "ASK_USER|CALL_TOOL|SHOW_CONFIRMATION|COMPLETE|FAIL|UNSUPPORTED|CANCEL",
                  "assistantMessage": "string",
                  "toolName": "string|null",
                  "requiredInputs": ["payeeName"|"amount"|"currency"|"selectedPayeeId"],
                  "draftUpdate": {
                    "payeeNameInput": "string|null",
                    "amount": 123.45,
                    "currency": "string|null",
                    "note": "string|null",
                    "selectedPayeeId": "string|null"
                  }
                }

                Planner context:
                %s
                """.formatted(
                availableTools,
                HeuristicJourneyAgentPlanner.TOOL_CONFIRM_DOMESTIC_PAYMENT,
                serializedContext);
    }

    private String formatToolDefinition(JourneyToolDefinition definition) {
        return "- %s: %s Guardrails: %s".formatted(
                definition.name(),
                definition.description(),
                definition.constraints());
    }

    private JourneyAgentDecision parseResponse(LlmChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            return null;
        }

        String body = response.response().trim();
        String jsonCandidate = extractJsonObject(body);
        if (jsonCandidate == null) {
            return null;
        }

        try {
            LlmStructuredResponse parsed = objectMapper.readValue(jsonCandidate, LlmStructuredResponse.class);
            JourneyAction action = parsed.action() == null ? null : JourneyAction.valueOf(parsed.action());
            JourneyDraftUpdate update = parsed.draftUpdate() == null
                    ? JourneyDraftUpdate.empty()
                    : new JourneyDraftUpdate(
                            parsed.draftUpdate().payeeNameInput(),
                            parsed.draftUpdate().amount(),
                            parsed.draftUpdate().currency(),
                            parsed.draftUpdate().note(),
                            parsed.draftUpdate().selectedPayeeId());
            return new JourneyAgentDecision(
                    action,
                    parsed.assistantMessage(),
                    parsed.toolName(),
                    parsed.requiredInputs() == null ? List.of() : parsed.requiredInputs(),
                    update);
        } catch (Exception exception) {
            LOGGER.debug("Unable to parse structured local LLM response", exception);
            return null;
        }
    }

    private String extractJsonObject(String responseText) {
        int start = responseText.indexOf('{');
        int end = responseText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return responseText.substring(start, end + 1);
    }

    private record LlmChatRequest(
            String message,
            @JsonProperty("session_id") String sessionId,
            List<String> attachments) {
    }

    private record LlmChatResponse(String response) {
    }

    private record LlmStructuredResponse(
            String action,
            String assistantMessage,
            String toolName,
            List<String> requiredInputs,
            DraftUpdatePayload draftUpdate) {
    }

    private record DraftUpdatePayload(
            String payeeNameInput,
            BigDecimal amount,
            String currency,
            String note,
            String selectedPayeeId) {
    }
}
