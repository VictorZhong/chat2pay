package com.chat2pay.app.integration.llm;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.config.Chat2PayProperties.UseCaseProperties;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Selects the LLM provider for a given call site.
 *
 * <p>Routing precedence:
 * <ol>
 *   <li>Per-use-case override at {@code chat2pay.use-cases.<key>.provider}, if
 *       configured and that provider is available.</li>
 *   <li>Global {@code chat2pay.primary-provider}, if available.</li>
 *   <li>Global {@code chat2pay.fallback-provider}, if available.</li>
 *   <li>Empty (callers must handle the missing-provider case gracefully).</li>
 * </ol>
 *
 * <p>The selection also carries an optional {@code modelOverride} so a use case
 * can pin a specific model name (e.g. "Qwen3-32B-AWQ" for cheap title
 * generation) without changing the provider's global default.
 */
@Component
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final Map<LlmProviderType, LlmProvider> providers = new EnumMap<>(LlmProviderType.class);
    private final LlmProviderType primary;
    private final LlmProviderType fallback;
    private final Map<String, UseCaseProperties> useCases;

    public LlmRouter(List<LlmProvider> providerBeans, Chat2PayProperties properties) {
        for (LlmProvider p : providerBeans) providers.put(p.providerType(), p);
        this.primary = parse(properties.primaryProvider(), LlmProviderType.COPILOT_PERSONAL);
        this.fallback = parse(properties.fallbackProvider(), LlmProviderType.REMOTE_API);
        this.useCases = normalize(properties.useCases());
    }

    // ---- legacy entry points (unchanged behavior) ----------------------------

    public LlmProvider current() {
        Optional<LlmProvider> available = currentIfAvailable();
        if (available.isPresent()) return available.get();
        log.warn("No LLM provider is available; returning primary {} (calls will fail until configured).", primary);
        return providers.getOrDefault(primary, providers.values().stream().findFirst().orElseThrow());
    }

    public Optional<LlmProvider> currentIfAvailable() {
        return select(LlmUseCase.CHAT).map(LlmSelection::provider);
    }

    // ---- per-use-case selection ---------------------------------------------

    /**
     * Resolve the provider + model override for a use case. Returns empty when
     * no provider in the chain (override → primary → fallback) is available.
     */
    public Optional<LlmSelection> select(LlmUseCase useCase) {
        UseCaseProperties override = useCase == null ? null : useCases.get(useCase.key());
        String modelOverride = override == null ? null : trimToNull(override.model());

        LlmProviderType overrideType = override == null
                ? null : parseOrNull(override.provider());
        if (overrideType != null) {
            LlmProvider direct = providers.get(overrideType);
            if (direct != null && direct.isAvailable()) {
                return Optional.of(new LlmSelection(direct, modelOverride));
            }
            log.warn("Use-case {} requested provider {} but it is unavailable; falling back to global routing.",
                    useCase, overrideType);
        }

        LlmProvider p = providers.get(primary);
        if (p != null && p.isAvailable()) return Optional.of(new LlmSelection(p, modelOverride));
        LlmProvider f = providers.get(fallback);
        if (f != null && f.isAvailable()) {
            log.warn("Primary LLM provider {} unavailable, using fallback {}", primary, fallback);
            return Optional.of(new LlmSelection(f, modelOverride));
        }
        return Optional.empty();
    }

    // ---- helpers -------------------------------------------------------------

    private static LlmProviderType parse(String raw, LlmProviderType defaultValue) {
        LlmProviderType parsed = parseOrNull(raw);
        return parsed == null ? defaultValue : parsed;
    }

    private static LlmProviderType parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LlmProviderType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, UseCaseProperties> normalize(Map<String, UseCaseProperties> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, UseCaseProperties> normalized = new java.util.HashMap<>();
        for (Map.Entry<String, UseCaseProperties> e : raw.entrySet()) {
            if (e.getKey() == null) continue;
            normalized.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        return Map.copyOf(normalized);
    }
}
