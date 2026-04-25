package com.chat2pay.app.persistence.jdbc;

import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registered payee directory backed by ctp_registered_payee and
 * ctp_payee_alias.
 */
@Repository
public class JdbcPayeeStore {

    public record RegisteredPayee(PayeeSummary summary, List<String> aliases) {}

    private final JdbcClient jdbc;

    public JdbcPayeeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<RegisteredPayee> all() {
        Map<String, List<String>> aliasIndex = loadAliases();
        return jdbc.sql("""
                select id, name, payee_type, bank_code, bank_name, account_number, display_label
                  from ctp_registered_payee
                 order by name, account_number
                """)
                .query((rs, rowNum) -> rowToPayee(rs, aliasIndex))
                .list();
    }

    public Optional<RegisteredPayee> findById(String payeeId) {
        Map<String, List<String>> aliasIndex = loadAliases();
        return jdbc.sql("""
                select id, name, payee_type, bank_code, bank_name, account_number, display_label
                  from ctp_registered_payee
                 where id = :id
                """)
                .param("id", payeeId)
                .query((rs, rowNum) -> rowToPayee(rs, aliasIndex))
                .optional();
    }

    public List<RegisteredPayee> findByQuery(String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        return all().stream()
                .filter(p -> p.summary().name().toLowerCase(Locale.ROOT).contains(normalized)
                        || p.aliases().stream().anyMatch(a ->
                                a.contains(normalized) || normalized.contains(a)))
                .toList();
    }

    public String findAliasInText(String text) {
        if (text == null) return null;
        String normalized = text.toLowerCase(Locale.ROOT);
        return knownAliases().stream()
                .sorted((a, b) -> b.length() - a.length())
                .filter(normalized::contains)
                .findFirst()
                .orElse(null);
    }

    public List<String> knownAliases() {
        return jdbc.sql("select distinct alias from ctp_payee_alias")
                .query(String.class)
                .list();
    }

    private RegisteredPayee rowToPayee(java.sql.ResultSet rs, Map<String, List<String>> aliasIndex) throws java.sql.SQLException {
        String id = rs.getString("id");
        PayeeSummary summary = new PayeeSummary(
                id,
                rs.getString("name"),
                rs.getString("payee_type"),
                rs.getString("bank_code"),
                rs.getString("bank_name"),
                rs.getString("account_number"),
                rs.getString("display_label")
        );
        return new RegisteredPayee(summary, aliasIndex.getOrDefault(id, List.of()));
    }

    private Map<String, List<String>> loadAliases() {
        Map<String, List<String>> map = new HashMap<>();
        jdbc.sql("select payee_id, alias from ctp_payee_alias")
                .query((rs, rowNum) -> {
                    map.computeIfAbsent(rs.getString("payee_id"), k -> new ArrayList<>())
                            .add(rs.getString("alias"));
                    return null;
                })
                .list();
        return map;
    }
}
