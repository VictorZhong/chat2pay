package com.chat2pay.app.integration.llm;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Selects the primary LLM provider based on Chat2PayProperties, with a
 * configured fallback if the primary is unavailable. The deterministic
 * orchestrator currently does not call the router on the chat hot path —
 * V1 keeps assistant responses rule-driven for the structured-block contract.
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final Map<LlmProviderType, LlmProvider> providers = new EnumMap<>(LlmProviderType.class);
    private final LlmProviderType primary;
    private final LlmProviderType fallback;

    public LlmRouter(List<LlmProvider> providerBeans, Chat2PayProperties properties) {
        for (LlmProvider p : providerBeans) providers.put(p.providerType(), p);
        this.primary = parse(properties.primaryProvider(), LlmProviderType.COPILOT_PERSONAL);
        this.fallback = parse(properties.fallbackProvider(), LlmProviderType.REMOTE_API);
    }

    public LlmProvider current() {
        LlmProvider p = providers.get(primary);
        if (p != null && p.isAvailable()) return p;
        LlmProvider f = providers.get(fallback);
        if (f != null && f.isAvailable()) {
            log.warn("Primary LLM provider {} unavailable, using fallback {}", primary, fallback);
            return f;
        }
        log.warn("No LLM provider is available; returning primary {} (calls will fail until configured).", primary);
        return providers.getOrDefault(primary, providers.values().stream().findFirst().orElseThrow());
    }

    private static LlmProviderType parse(String raw, LlmProviderType defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return LlmProviderType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return defaultValue;
        }
    }
}
