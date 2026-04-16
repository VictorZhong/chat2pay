package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.PayeeMatchResult;
import com.chat2pay.app.integration.downstream.RegisteredPayee;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PayeeMatcher {

    public PayeeMatchResult match(String requestedPayeeName, List<RegisteredPayee> payees) {
        if (requestedPayeeName == null || requestedPayeeName.isBlank()) {
            return new PayeeMatchResult(List.of());
        }

        String normalizedRequested = normalize(requestedPayeeName);
        List<RegisteredPayee> exact = payees.stream()
                .filter(payee -> normalize(payee.name()).equals(normalizedRequested))
                .toList();
        if (!exact.isEmpty()) {
            return new PayeeMatchResult(exact);
        }

        Set<RegisteredPayee> rankedMatches = new LinkedHashSet<>();
        for (RegisteredPayee payee : payees) {
            String normalizedPayee = normalize(payee.name());
            if (normalizedPayee.contains(normalizedRequested) || normalizedRequested.contains(normalizedPayee)) {
                rankedMatches.add(payee);
            }
        }

        if (!rankedMatches.isEmpty()) {
            return new PayeeMatchResult(new ArrayList<>(rankedMatches));
        }

        List<String> requestedTokens = List.of(normalizedRequested.split(" "));
        for (RegisteredPayee payee : payees) {
            String normalizedPayee = normalize(payee.name());
            boolean allTokensPresent = requestedTokens.stream()
                    .filter(token -> !token.isBlank())
                    .allMatch(normalizedPayee::contains);
            if (allTokensPresent) {
                rankedMatches.add(payee);
            }
        }

        return new PayeeMatchResult(new ArrayList<>(rankedMatches));
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
