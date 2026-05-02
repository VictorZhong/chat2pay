package com.chat2pay.app.integration.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.config.Chat2PayProperties.LlmProviderType;
import com.chat2pay.app.config.Chat2PayProperties.RemoteModel;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RemoteLlmClientTest {

    private static final String MODEL_URL = "https://example.test/qwen/v1/chat/completions";

    private Chat2PayProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RemoteLlmTokenService tokenService;

    @BeforeEach
    void setUp() {
        properties = new Chat2PayProperties();
        properties.getLlm().getRemote().setEnabled(true);
        properties.getLlm().getRemote().setDefaultUser("UC0006040");

        RemoteModel model = new RemoteModel();
        model.setName("Qwen3-32B-AWQ");
        model.setUrl(MODEL_URL);
        properties.getLlm().getRemote().setModels(java.util.List.of(model));

        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokenService = Mockito.mock(RemoteLlmTokenService.class);
        Mockito.when(tokenService.issueToken()).thenReturn("jwt-abc");
    }

    @Test
    void sendsAuthHeadersAndReturnsAssistantContent() {
        server.expect(requestTo(MODEL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-zzzz-E2E-Trust-Token", "jwt-abc"))
                .andExpect(header("X-zzzz-Request-Correlation-Id", Matchers.matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("Qwen3-32B-AWQ"))
                .andExpect(jsonPath("$.user").value("UC0006040"))
                .andExpect(jsonPath("$.max_tokens").value(150))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("hi"))
                .andRespond(withSuccess(
                        """
                        {
                          "choices": [
                            {"index": 0, "message": {"role":"assistant","content":"hello"}, "finish_reason":"stop"}
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON));

        RemoteLlmClient client = new RemoteLlmClient(properties, tokenService, builder);

        LlmCompletion completion = client.complete(new LlmRequest("hi", 150), "Qwen3-32B-AWQ");

        assertThat(completion.content()).isEqualTo("hello");
        assertThat(completion.provider()).isEqualTo(LlmProviderType.REMOTE);
        assertThat(completion.model()).isEqualTo("Qwen3-32B-AWQ");
        server.verify();
    }

    @Test
    void wrapsHttpFailureInLlmUnavailableException() {
        server.expect(requestTo(MODEL_URL)).andRespond(withServerError());
        RemoteLlmClient client = new RemoteLlmClient(properties, tokenService, builder);

        assertThatThrownBy(() -> client.complete(new LlmRequest("hi", 50), "Qwen3-32B-AWQ"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("Qwen3-32B-AWQ");
    }

    @Test
    void rejectsUnknownModel() {
        RemoteLlmClient client = new RemoteLlmClient(properties, tokenService, builder);

        assertThatThrownBy(() -> client.complete(new LlmRequest("hi", 50), "non-existent"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("non-existent");
    }

    @Test
    void rejectsCallWhenRemoteDisabled() {
        properties.getLlm().getRemote().setEnabled(false);
        RemoteLlmClient client = new RemoteLlmClient(properties, tokenService, builder);

        assertThatThrownBy(() -> client.complete(new LlmRequest("hi", 50), "Qwen3-32B-AWQ"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("disabled");
    }
}
