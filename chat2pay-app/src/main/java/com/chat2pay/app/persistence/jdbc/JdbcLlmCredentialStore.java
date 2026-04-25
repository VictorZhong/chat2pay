package com.chat2pay.app.persistence.jdbc;

import com.chat2pay.app.domain.conversation.LlmProviderType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Read/write access to ctp_llm_credential. The api_key column holds the
 * GitHub PAT used for the Copilot session-token exchange. Operators rotate it
 * via plain SQL; the provider re-reads on every cache miss so no restart is
 * required.
 */
@Repository
public class JdbcLlmCredentialStore {

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

    private final JdbcClient jdbc;

    public JdbcLlmCredentialStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Credential> find(LlmProviderType provider) {
        return jdbc.sql("""
                select provider, api_key, session_token, session_token_expires_at, updated_at
                  from ctp_llm_credential
                 where provider = :p
                """)
                .param("p", provider.name())
                .query((rs, rn) -> new Credential(
                        LlmProviderType.valueOf(rs.getString("provider")),
                        rs.getString("api_key"),
                        rs.getString("session_token"),
                        rs.getTimestamp("session_token_expires_at") == null ? null
                                : rs.getTimestamp("session_token_expires_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()
                ))
                .optional();
    }

    @Transactional
    public void upsertSessionToken(LlmProviderType provider, String token, Instant expiresAt) {
        jdbc.sql("""
                insert into ctp_llm_credential (provider, session_token, session_token_expires_at, updated_at)
                values (:p, :t, :exp, :now)
                on conflict (provider) do update set
                    session_token = excluded.session_token,
                    session_token_expires_at = excluded.session_token_expires_at,
                    updated_at = excluded.updated_at
                """)
                .param("p", provider.name())
                .param("t", token)
                .param("exp", expiresAt == null ? null : Timestamp.from(expiresAt))
                .param("now", Timestamp.from(Instant.now()))
                .update();
    }

    /**
     * Optional bootstrap path: when ctp_llm_credential.api_key is empty but the
     * operator has supplied an env-bootstrap value via application.yml, we
     * write it to the table so future rotations are SQL-only.
     */
    @Transactional
    public void bootstrapIfMissing(LlmProviderType provider, String apiKey, String sessionToken) {
        if ((apiKey == null || apiKey.isBlank()) && (sessionToken == null || sessionToken.isBlank())) return;
        jdbc.sql("""
                insert into ctp_llm_credential (provider, api_key, session_token, updated_at)
                values (:p, :k, :t, :now)
                on conflict (provider) do nothing
                """)
                .param("p", provider.name())
                .param("k", apiKey == null || apiKey.isBlank() ? null : apiKey)
                .param("t", sessionToken == null || sessionToken.isBlank() ? null : sessionToken)
                .param("now", Timestamp.from(Instant.now()))
                .update();
    }
}
