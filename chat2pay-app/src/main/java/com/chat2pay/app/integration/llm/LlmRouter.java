package com.chat2pay.app.integration.llm;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.config.Chat2PayProperties.LlmProviderType;
import com.chat2pay.app.config.Chat2PayProperties.UseCaseConfig;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LlmRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmRouter.class);

    private final Chat2PayProperties properties;
    private final Map<LlmProviderType, LlmClient> clients;

    public LlmRouter(Chat2PayProperties properties, List<LlmClient> clients) {
        this.properties = properties;
        EnumMap<LlmProviderType, LlmClient> map = new EnumMap<>(LlmProviderType.class);
        for (LlmClient client : clients) {
            map.put(client.providerType(), client);
        }
        this.clients = map;
    }

    public LlmCompletion complete(String useCaseName, String prompt) {
        UseCaseConfig useCase = resolveUseCase(useCaseName);
        LlmRequest request = new LlmRequest(prompt, useCase.getMaxTokens());

        LlmProviderType primary = useCase.getProvider();
        try {
            return invoke(primary, useCase.getModel(), request);
        } catch (LlmUnavailableException primaryFailure) {
            LlmProviderType fallback = useCase.getFallbackProvider();
            if (fallback == null || fallback == primary) {
                throw primaryFailure;
            }
            LOGGER.debug("Primary provider {} failed for use case {}, falling back to {}",
                    primary, useCaseName, fallback, primaryFailure);
            try {
                String fallbackModel = fallback == LlmProviderType.REMOTE ? useCase.getModel() : null;
                return invoke(fallback, fallbackModel, request);
            } catch (LlmUnavailableException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    private LlmCompletion invoke(LlmProviderType provider, String model, LlmRequest request) {
        LlmClient client = clients.get(provider);
        if (client == null) {
            throw new LlmUnavailableException("No LLM client registered for provider " + provider);
        }
        return client.complete(request, model);
    }

    private UseCaseConfig resolveUseCase(String useCaseName) {
        UseCaseConfig configured = properties.getLlm().getUseCases().get(useCaseName);
        if (configured != null) {
            return configured;
        }
        UseCaseConfig defaults = new UseCaseConfig();
        defaults.setProvider(LlmProviderType.COPILOT);
        return defaults;
    }
}
