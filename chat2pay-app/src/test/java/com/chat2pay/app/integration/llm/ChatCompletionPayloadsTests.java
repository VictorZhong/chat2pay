package com.chat2pay.app.integration.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCompletionPayloadsTests {

    @Test
    void rendersAssistantToolCallsAndToolResultMessages() {
        List<Map<String, Object>> messages = ChatCompletionPayloads.messages(List.of(
                LlmCompletionRequest.Message.assistantToolCalls(List.of(
                        new LlmCompletionRequest.ToolCall("call_1", "get_registered_payees", "{\"name_query\":\"bob\"}")
                )),
                LlmCompletionRequest.Message.toolResult(
                        "call_1",
                        "get_registered_payees",
                        "{\"ok\":true,\"match_count\":1}"
                )
        ));

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0))
                .containsEntry("role", "assistant")
                .containsEntry("content", "");
        assertThat(messages.get(0).get("tool_calls")).asList().hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> toolCall = (Map<String, Object>) ((List<?>) messages.get(0).get("tool_calls")).get(0);
        assertThat(toolCall)
                .containsEntry("id", "call_1")
                .containsEntry("type", "function");
        assertThat(toolCall.get("function"))
                .isEqualTo(Map.of("name", "get_registered_payees", "arguments", "{\"name_query\":\"bob\"}"));

        assertThat(messages.get(1))
                .containsEntry("role", "tool")
                .containsEntry("name", "get_registered_payees")
                .containsEntry("tool_call_id", "call_1")
                .containsEntry("content", "{\"ok\":true,\"match_count\":1}");
    }
}
