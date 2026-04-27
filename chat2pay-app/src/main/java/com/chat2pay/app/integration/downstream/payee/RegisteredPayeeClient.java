package com.chat2pay.app.integration.downstream.payee;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Client boundary for registered-payee lookup. Real-mode payees come from the bank
 * payee API which returns a contact-with-accounts hierarchy: each contact has a
 * nickname and a full name plus one or more financial addresses (accounts). When an
 * account is an integrated wrapper with sub-accounts, only the leaf sub-accounts are
 * payable, so the parser flattens those out before they reach this interface.
 */
public interface RegisteredPayeeClient {

    List<DownstreamPayee> loadPayees(String profileId);

    default List<DownstreamPayee> findPayees(String profileId, String nameQuery) {
        if (nameQuery == null || nameQuery.isBlank()) return loadPayees(profileId);
        String normalized = nameQuery.toLowerCase(Locale.ROOT).trim();
        return loadPayees(profileId).stream()
                .filter(p -> nameMatches(p, normalized))
                .toList();
    }

    /**
     * Find the (payee, account) pair whose account.addressId matches. Accounts are
     * globally unique by addressId, so this is sufficient for a downstream payment
     * call which uses addressId as the payeeIdIndex.
     */
    default Optional<PayeeAccountRef> findAccountByAddressId(String profileId, String addressId) {
        if (addressId == null || addressId.isBlank()) return Optional.empty();
        for (DownstreamPayee payee : loadPayees(profileId)) {
            for (DownstreamAccount account : payee.accounts()) {
                if (addressId.equals(account.addressId())) {
                    return Optional.of(new PayeeAccountRef(payee, account));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean nameMatches(DownstreamPayee p, String normalized) {
        return contains(p.nickName(), normalized)
                || contains(p.contactFullName(), normalized);
    }

    private static boolean contains(String value, String normalized) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalized);
    }

    record PayeeAccountRef(DownstreamPayee payee, DownstreamAccount account) {}

    record DownstreamPayee(
            String contactId,
            String contactIdentifier,
            String nickName,
            String contactFullName,
            String contactType,
            String contactSource,
            List<DownstreamAccount> accounts
    ) {
        public DownstreamPayee {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
        }
    }

    record DownstreamAccount(
            // Globally-unique account identifier; passed downstream as payeeIdIndex.
            String addressId,
            String identifierType,
            String identifierCode,           // bank code (e.g. "004")
            String identifierNumber,
            String formattedAccountNumber,
            String bankCountryCode,
            String bankName,
            String addrFullName,
            String accountProductType,       // e.g. "zzzz HKD Savings"
            String accountProductCode,       // e.g. "HKDAVSAV"
            String payeeType,                // legacy code "2"/"3"/"8"
            String paymentType,              // typically "DOMESTIC"
            String payeeAccountLabel,        // "Local" (domestic-only) or "International" (cross-border)
            BigDecimal accountLimit,
            String accountLimitCurrency,
            String remittanceCurrencyCode    // null/blank → caller falls back to accountLimitCurrency
    ) {
        /** Currency the account actually pays in, with the documented fallback applied. */
        public String effectiveRemittanceCurrency() {
            if (remittanceCurrencyCode != null && !remittanceCurrencyCode.isBlank()) {
                return remittanceCurrencyCode;
            }
            return accountLimitCurrency;
        }

        /** True when the account is restricted to domestic payments. */
        public boolean isLocal() {
            return "Local".equalsIgnoreCase(payeeAccountLabel);
        }

        /** True when the account is for cross-border / international payments. */
        public boolean isInternational() {
            return "International".equalsIgnoreCase(payeeAccountLabel);
        }
    }
}
