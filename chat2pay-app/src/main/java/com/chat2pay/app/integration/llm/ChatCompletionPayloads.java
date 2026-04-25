package com.chat2pay.app.integration.llm;

import com.chat2pay.app.integration.llm.LlmCompletionRequest.Message;
import com.chat2pay.app.integration.llm.LlmCompletionRequest.ToolDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ChatCompletionPayloads {

    private ChatCompletionPayloads() {}

    public static List<Map<String, Object>> messages(List<Message> messages) {
        return messages.stream().map(ChatCompletionPayloads::message).toList();
    }

    public static List<Map<String, Object>> tools(List<ToolDefinition> tools) {
        return tools.stream()
                .map(tool -> Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", tool.parameters()
                        )
                ))
                .toList();
    }

    private static Map<String, Object> message(Message message) {
        Map<String, Object> body = new HashMap<>();
        body.put("role", message.role().name().toLowerCase(Locale.ROOT));
        body.put("content", message.content() == null ? "" : message.content());
        if (message.name() != null && !message.name().isBlank()) {
            body.put("name", message.name());
        }
        if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
            body.put("tool_call_id", message.toolCallId());
        }
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            body.put("tool_calls", message.toolCalls().stream()
                    .map(tc -> Map.of(
                            "id", tc.id(),
                            "type", "function",
                            "function", Map.of(
                                    "name", tc.name(),
                                    "arguments", tc.arguments() == null ? "{}" : tc.arguments()
                            )
                    ))
                    .toList());
        }
        return body;
    }
}
