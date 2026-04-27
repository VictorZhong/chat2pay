package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.integration.downstream.payee.RegisteredPayeeClient;
import com.chat2pay.app.integration.downstream.payee.RegisteredPayeeClient.DownstreamAccount;
import com.chat2pay.app.integration.downstream.payee.RegisteredPayeeClient.DownstreamPayee;
import com.chat2pay.app.persistence.entity.RegisteredPayeeEntity;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Registered-payee lookup facade. Real downstream mode fetches payees live from
 * the configured API; the local tables are used only for mock POC fixtures.
 *
 * The model is two-level: a {@link RegisteredPayee} (one contact / nickname / full
 * name) holds one or more {@link RegisteredAccount}s. addressId is the globally
 * unique account identifier and is used as payeeIdIndex when calling downstream.
 */
@Repository
public class PayeeStore {

    public record RegisteredPayee(
            String contactId,
            String nickName,
            String contactFullName,
            List<String> aliases,
            List<RegisteredAccount> accounts
    ) {
        public RegisteredPayee {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
        }

        /** Display name used in chat messages — prefers nickname, falls back to full name. */
        public String displayName() {
            return nickName != null && !nickName.isBlank() ? nickName : contactFullName;
        }
    }

    public record RegisteredAccount(
            String addressId,
            String payeeType,
            String bankCode,
            String bankName,
            String accountNumber,        // formattedAccountNumber
            String accountProductType,
            String accountProductCode,
            String payeeAccountLabel,    // "Local" or "International"
            BigDecimal accountLimit,
            String accountLimitCurrency,
            String remittanceCurrencyCode
    ) {
        public boolean isLocal() {
            return "Local".equalsIgnoreCase(payeeAccountLabel);
        }

        public boolean isInternational() {
            return "International".equalsIgnoreCase(payeeAccountLabel);
        }

        public String effectiveRemittanceCurrency() {
            if (remittanceCurrencyCode != null && !remittanceCurrencyCode.isBlank()) {
                return remittanceCurrencyCode;
            }
            return accountLimitCurrency;
        }

        /** Short label combining product type and formatted account number. */
        public String displayLabel() {
            String product = accountProductType == null ? "" : accountProductType.trim();
            String number = accountNumber == null ? "" : accountNumber.trim();
            if (product.isEmpty()) return number;
            if (number.isEmpty()) return product;
            return product + " - " + number;
        }
    }

    public record PayeeAccountRef(RegisteredPayee payee, RegisteredAccount account) {}

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

    public List<RegisteredPayee> all(String profileId) {
        if (!properties.downstreamMockEnabled()) {
            return downstreamPayees.loadPayees(profileId).stream()
                    .map(PayeeStore::map)
                    .toList();
        }
        return payees.findByProfileIdOrderByNameAscAccountNumberAsc(profileId).stream()
                .map(PayeeStore::map)
                .toList();
    }

    /** Find a (payee, account) pair by addressId — used after the user picks an account. */
    public Optional<PayeeAccountRef> findAccount(String profileId, String addressId) {
        if (addressId == null || addressId.isBlank()) return Optional.empty();
        for (RegisteredPayee payee : all(profileId)) {
            for (RegisteredAccount account : payee.accounts()) {
                if (addressId.equals(account.addressId())) {
                    return Optional.of(new PayeeAccountRef(payee, account));
                }
            }
        }
        return Optional.empty();
    }

    public List<RegisteredPayee> findByQuery(String profileId, String query) {
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.toLowerCase(Locale.ROOT).trim();
        return all(profileId).stream()
                .filter(p -> matches(p, normalized))
                .toList();
    }

    /** Detect a known payee alias mentioned in free-form text (mock mode only). */
    public String findAliasInText(String profileId, String text) {
        if (text == null) return null;
        if (!properties.downstreamMockEnabled()) return null;
        String normalized = text.toLowerCase(Locale.ROOT);
        return knownAliases(profileId).stream()
                .sorted((a, b) -> b.length() - a.length())
                .filter(normalized::contains)
                .findFirst()
                .orElse(null);
    }

    public List<String> knownAliases(String profileId) {
        if (!properties.downstreamMockEnabled()) return List.of();
        return all(profileId).stream()
                .flatMap(p -> p.aliases().stream())
                .map(a -> a.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /** Build the flat {@link PayeeSummary} stored on a draft after the user picks an account. */
    public static PayeeSummary toSummary(RegisteredPayee payee, RegisteredAccount account) {
        return new PayeeSummary(
                account.addressId(),
                payee.displayName(),
                account.payeeType(),
                account.bankCode(),
                account.bankName(),
                account.accountNumber(),
                account.displayLabel()
        );
    }

    private static boolean matches(RegisteredPayee p, String normalized) {
        if (containsCi(p.nickName(), normalized) || containsCi(p.contactFullName(), normalized)) return true;
        for (String alias : p.aliases()) {
            String lc = alias.toLowerCase(Locale.ROOT);
            if (lc.contains(normalized) || normalized.contains(lc)) return true;
        }
        return false;
    }

    private static boolean containsCi(String value, String normalized) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalized);
    }

    // ---- mappers ----------------------------------------------------------

    private static RegisteredPayee map(RegisteredPayeeEntity p) {
        // Mock-mode rows are flat (one payee = one account); wrap into the new shape.
        RegisteredAccount account = new RegisteredAccount(
                p.getId(),
                p.getPayeeType(),
                p.getBankCode(),
                p.getBankName(),
                p.getAccountNumber(),
                accountProductTypeFromLabel(p.getDisplayLabel()),
                null,
                "Local",
                null,
                "HKD",
                "HKD"
        );
        List<String> aliases = new ArrayList<>();
        if (p.getAliases() != null) aliases.addAll(p.getAliases());
        addAlias(aliases, p.getName());
        return new RegisteredPayee(
                null,
                p.getName(),
                p.getName(),
                aliases,
                List.of(account)
        );
    }

    private static RegisteredPayee map(DownstreamPayee p) {
        List<RegisteredAccount> accounts = p.accounts().stream()
                .map(PayeeStore::map)
                .toList();
        // Aliases from nickname + full name only — real-mode does not derive a wider alias set.
        List<String> aliases = new ArrayList<>();
        addAlias(aliases, p.nickName());
        addAlias(aliases, p.contactFullName());
        return new RegisteredPayee(
                p.contactId(),
                p.nickName(),
                p.contactFullName(),
                aliases,
                accounts
        );
    }

    private static RegisteredAccount map(DownstreamAccount a) {
        return new RegisteredAccount(
                a.addressId(),
                a.payeeType(),
                a.identifierCode(),
                a.bankName(),
                a.formattedAccountNumber(),
                a.accountProductType(),
                a.accountProductCode(),
                a.payeeAccountLabel(),
                a.accountLimit(),
                a.accountLimitCurrency(),
                a.remittanceCurrencyCode()
        );
    }

    private static void addAlias(List<String> aliases, String value) {
        if (value == null || value.isBlank()) return;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isBlank()) return;
        Set<String> seen = new HashSet<>(aliases);
        if (seen.add(trimmed)) aliases.add(trimmed);
        // Tokenize: surface common single-name forms ("alice chan" → "alice", "chan").
        for (String token : trimmed.split("\\s+")) {
            if (token.length() < 2) continue;
            if (seen.add(token)) aliases.add(token);
        }
    }

    private static String accountProductTypeFromLabel(String displayLabel) {
        if (displayLabel == null) return null;
        int sep = displayLabel.indexOf("•");
        if (sep > 0) return displayLabel.substring(0, sep).trim();
        return displayLabel;
    }
}
