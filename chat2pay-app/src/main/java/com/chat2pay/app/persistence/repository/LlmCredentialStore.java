package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.persistence.entity.LlmCredentialEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Read/write access to ctp_llm_credential. The api_key column holds the GitHub
 * PAT used for the Copilot session-token exchange. Operators rotate it via
 * SQL; the provider re-reads on every cache miss so no restart is required.
 */
@Repository
public class LlmCredentialStore {

    private static final Duration BOOTSTRAP_SESSION_TTL = Duration.ofMinutes(25);

    public record Credential(
            LlmProviderType provider,
            String apiKey,
            String sessionToken,
            Instant sessionTokenExpiresAt,
            Instant updatedAt
    ) {
        public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }

        public boolean hasFreshSessionToken(Instant now) {
            return sessionToken != null && !sessionToken.isBlank()
                    && sessionTokenExpiresAt != null
                    && sessionTokenExpiresAt.isAfter(now.plusSeconds(30));
        }
    }

    private final LlmCredentialRepository credentials;
    private final TransactionTemplate transactions;

    public LlmCredentialStore(LlmCredentialRepository credentials, TransactionTemplate transactions) {
        this.credentials = credentials;
        this.transactions = transactions;
    }

    public Optional<Credential> find(LlmProviderType provider) {
        return credentials.findById(provider).map(this::map);
    }

    public void upsertSessionToken(LlmProviderType provider, String token, Instant expiresAt) {
        transactions.executeWithoutResult(status -> {
            LlmCredentialEntity entity = credentials.findById(provider).orElseGet(() -> {
                LlmCredentialEntity created = new LlmCredentialEntity();
                created.setProvider(provider);
                return created;
            });
            entity.setSessionToken(nullIfBlank(token));
            entity.setSessionTokenExpiresAt(token == null || token.isBlank() ? null : expiresAt);
            entity.setUpdatedAt(Instant.now());
            credentials.save(entity);
        });
    }

    /**
     * Optional bootstrap path: when the DB credential row is missing but the
     * operator supplied env bootstrap values, persist them once. Future
     * rotations remain SQL-only.
     */
    public void bootstrapIfMissing(LlmProviderType provider, String apiKey, String sessionToken) {
        if ((apiKey == null || apiKey.isBlank()) && (sessionToken == null || sessionToken.isBlank())) return;
        transactions.executeWithoutResult(status -> {
            if (credentials.existsById(provider)) return;
            LlmCredentialEntity entity = new LlmCredentialEntity();
            entity.setProvider(provider);
            entity.setApiKey(nullIfBlank(apiKey));
            entity.setSessionToken(nullIfBlank(sessionToken));
            if (sessionToken != null && !sessionToken.isBlank()) {
                entity.setSessionTokenExpiresAt(Instant.now().plus(BOOTSTRAP_SESSION_TTL));
            }
            entity.setUpdatedAt(Instant.now());
            credentials.save(entity);
        });
    }

    private Credential map(LlmCredentialEntity e) {
        return new Credential(
                e.getProvider(),
                e.getApiKey(),
                e.getSessionToken(),
                e.getSessionTokenExpiresAt(),
                e.getUpdatedAt()
        );
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
