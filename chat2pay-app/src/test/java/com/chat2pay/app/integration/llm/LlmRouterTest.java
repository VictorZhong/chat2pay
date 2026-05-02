package com.chat2pay.app.integration.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.config.Chat2PayProperties.LlmProviderType;
import com.chat2pay.app.config.Chat2PayProperties.UseCaseConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmRouterTest {

    @Test
    void routesUseCaseToConfiguredPrimaryProvider() {
        Chat2PayProperties properties = new Chat2PayProperties();
        UseCaseConfig journey = new UseCaseConfig();
        journey.setProvider(LlmProviderType.REMOTE);
        journey.setModel("Qwen3-32B-AWQ");
        journey.setMaxTokens(123);
        properties.getLlm().getUseCases().put("journey-planner", journey);

        StubClient copilot = new StubClient(LlmProviderType.COPILOT, "copilot-said");
        StubClient remote = new StubClient(LlmProviderType.REMOTE, "remote-said");

        LlmRouter router = new LlmRouter(properties, List.of(copilot, remote));

        LlmCompletion result = router.complete("journey-planner", "prompt");

        assertThat(result.content()).isEqualTo("remote-said");
        assertThat(remote.lastModel).isEqualTo("Qwen3-32B-AWQ");
        assertThat(remote.lastRequest.maxTokens()).isEqualTo(123);
        assertThat(copilot.callCount).isZero();
    }

    @Test
    void fallsBackToSecondaryProviderWhenPrimaryFails() {
        Chat2PayProperties properties = new Chat2PayProperties();
        UseCaseConfig journey = new UseCaseConfig();
        journey.setProvider(LlmProviderType.REMOTE);
        journey.setModel("Qwen3-32B-AWQ");
        journey.setFallbackProvider(LlmProviderType.COPILOT);
        properties.getLlm().getUseCases().put("journey-planner", journey);

        StubClient copilot = new StubClient(LlmProviderType.COPILOT, "copilot-said");
        ThrowingClient remote = new ThrowingClient(LlmProviderType.REMOTE);

        LlmRouter router = new LlmRouter(properties, List.of(copilot, remote));

        LlmCompletion result = router.complete("journey-planner", "prompt");

        assertThat(result.content()).isEqualTo("copilot-said");
        assertThat(copilot.callCount).isEqualTo(1);
    }

    @Test
    void rethrowsPrimaryFailureWhenNoFallbackConfigured() {
        Chat2PayProperties properties = new Chat2PayProperties();
        UseCaseConfig journey = new UseCaseConfig();
        journey.setProvider(LlmProviderType.REMOTE);
        journey.setModel("Qwen3-32B-AWQ");
        properties.getLlm().getUseCases().put("journey-planner", journey);

        ThrowingClient remote = new ThrowingClient(LlmProviderType.REMOTE);
        LlmRouter router = new LlmRouter(properties, List.of(remote));

        assertThatThrownBy(() -> router.complete("journey-planner", "prompt"))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void defaultsToCopilotWhenUseCaseNotConfigured() {
        Chat2PayProperties properties = new Chat2PayProperties();
        StubClient copilot = new StubClient(LlmProviderType.COPILOT, "copilot-said");
        LlmRouter router = new LlmRouter(properties, List.of(copilot));

        LlmCompletion result = router.complete("unknown-use-case", "prompt");

        assertThat(result.content()).isEqualTo("copilot-said");
        assertThat(copilot.lastModel).isNull();
    }

    private static class StubClient implements LlmClient {
        final LlmProviderType providerType;
        final String reply;
        int callCount;
        LlmRequest lastRequest;
        String lastModel;

        StubClient(LlmProviderType providerType, String reply) {
            this.providerType = providerType;
            this.reply = reply;
        }

        @Override
        public LlmProviderType providerType() {
            return providerType;
        }

        @Override
        public LlmCompletion complete(LlmRequest request, String model) {
            callCount++;
            lastRequest = request;
            lastModel = model;
            return new LlmCompletion(reply, providerType, model);
        }
    }

    private static class ThrowingClient implements LlmClient {
        final LlmProviderType providerType;
        final List<LlmRequest> received = new ArrayList<>();

        ThrowingClient(LlmProviderType providerType) {
            this.providerType = providerType;
        }

        @Override
        public LlmProviderType providerType() {
            return providerType;
        }

        @Override
        public LlmCompletion complete(LlmRequest request, String model) {
            received.add(request);
            throw new LlmUnavailableException("nope");
        }
    }
}
