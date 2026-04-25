package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.integration.downstream.payee.RegisteredPayeeClient;
import com.chat2pay.app.integration.downstream.payee.RegisteredPayeeClient.DownstreamPayee;
import com.chat2pay.app.persistence.entity.RegisteredPayeeEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Registered payee directory backed by ctp_registered_payee and ctp_payee_alias.
 */
@Repository
public class PayeeStore {

    public record RegisteredPayee(PayeeSummary summary, List<String> aliases) {}

    private final RegisteredPayeeRepository payees;
    private final RegisteredPayeeClient downstreamPayees;
    private final Chat2PayProperties properties;

    public PayeeStore(RegisteredPayeeRepository payees,
                      RegisteredPayeeClient downstreamPayees,
                      Chat2PayProperties properties) {
        this.payees = payees;
        this.downstreamPayees = downstreamPayees;
        this.properties = properties;
    }

    public List<RegisteredPayee> all() {
        if (!properties.downstreamMockEnabled()) {
            return downstreamPayees.loadPayees().stream()
                    .map(this::map)
                    .toList();
        }
        return payees.findAllByOrderByNameAscAccountNumberAsc().stream()
                .map(this::map)
                .toList();
    }

    public Optional<RegisteredPayee> findById(String payeeId) {
        if (!properties.downstreamMockEnabled()) {
            return downstreamPayees.findByPayeeIdIndex(payeeId).map(this::map);
        }
        return payees.findById(payeeId).map(this::map);
    }

    public List<RegisteredPayee> findByQuery(String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        return all().stream()
                .filter(p -> p.summary().name().toLowerCase(Locale.ROOT).contains(normalized)
                        || p.aliases().stream().anyMatch(a -> {
                            String alias = a.toLowerCase(Locale.ROOT);
                            return alias.contains(normalized) || normalized.contains(alias);
                        }))
                .toList();
    }

    public String findAliasInText(String text) {
        if (text == null) return null;
        if (!properties.downstreamMockEnabled()) return null;
        String normalized = text.toLowerCase(Locale.ROOT);
        return knownAliases().stream()
                .sorted((a, b) -> b.length() - a.length())
                .filter(normalized::contains)
                .findFirst()
                .orElse(null);
    }

    public List<String> knownAliases() {
        if (!properties.downstreamMockEnabled()) return List.of();
        return all().stream()
                .flatMap(p -> p.aliases().stream())
                .map(a -> a.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private RegisteredPayee map(RegisteredPayeeEntity p) {
        PayeeSummary summary = new PayeeSummary(
                p.getId(),
                p.getName(),
                p.getPayeeType(),
                p.getBankCode(),
                p.getBankName(),
                p.getAccountNumber(),
                p.getDisplayLabel()
        );
        return new RegisteredPayee(summary, p.getAliases() == null ? List.of() : List.copyOf(p.getAliases()));
    }

    private RegisteredPayee map(DownstreamPayee p) {
        PayeeSummary summary = new PayeeSummary(
                p.payeeIdIndex(),
                p.name(),
                p.payeeType(),
                p.bankCode(),
                p.bankName(),
                p.accountNumber(),
                p.displayLabel()
        );
        return new RegisteredPayee(summary, List.of(p.name().toLowerCase(Locale.ROOT)));
    }
}
