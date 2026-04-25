package com.chat2pay.app.integration.downstream.payee;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Client boundary for registered-payee lookup.
 */
public interface RegisteredPayeeClient {

    List<DownstreamPayee> loadPayees(String profileId);

    default List<DownstreamPayee> findPayees(String profileId, String nameQuery) {
        if (nameQuery == null || nameQuery.isBlank()) return loadPayees(profileId);
        String normalized = nameQuery.toLowerCase(Locale.ROOT).trim();
        return loadPayees(profileId).stream()
                .filter(p -> p.name().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    default Optional<DownstreamPayee> findByPayeeIdIndex(String profileId, String payeeIdIndex) {
        if (payeeIdIndex == null || payeeIdIndex.isBlank()) return Optional.empty();
        return loadPayees(profileId).stream()
                .filter(p -> payeeIdIndex.equals(p.payeeIdIndex()))
                .findFirst();
    }

    record DownstreamPayee(
            int selectionIndex,
            String payeeIdIndex,
            String name,
            String payeeType,
            String bankCode,
            String bankName,
            String accountNumber,
            String accountProductType,
            String accountProductCode,
            String remittanceCurrencyCode,
            String displayLabel
    ) {}
}
