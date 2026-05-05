package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.api.dto.ChatDtos.DebitAccountSummary;
import com.chat2pay.app.persistence.entity.ProfileDebitAccountEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Repository
public class ProfileDebitAccountStore {

    private static final String LEGACY_ID_PREFIX = "legacy:";

    private final ProfileDebitAccountRepository accounts;
    private final ProfileStore profiles;

    public ProfileDebitAccountStore(ProfileDebitAccountRepository accounts, ProfileStore profiles) {
        this.accounts = accounts;
        this.profiles = profiles;
    }

    public List<DebitAccountSummary> list(String profileId) {
        List<DebitAccountSummary> configured = accounts.findByProfileIdOrderBySortOrderAscCreatedAtAsc(profileId)
                .stream()
                .map(this::map)
                .toList();
        if (!configured.isEmpty()) return configured;
        return fallback(profileId).map(List::of).orElseGet(List::of);
    }

    public Optional<DebitAccountSummary> find(String profileId, String accountId) {
        if (accountId == null || accountId.isBlank()) return Optional.empty();
        Optional<DebitAccountSummary> configured = accounts.findByIdAndProfileId(accountId, profileId)
                .map(this::map);
        if (configured.isPresent()) return configured;
        Optional<DebitAccountSummary> fallback = fallback(profileId);
        if (fallback.isPresent() && accountId.equals(fallback.get().accountId())) return fallback;
        return Optional.empty();
    }

    private DebitAccountSummary map(ProfileDebitAccountEntity entity) {
        return new DebitAccountSummary(
                entity.getId(),
                entity.getAccountNumber(),
                entity.getProductCategoryCode(),
                firstNonBlank(entity.getDisplayLabel(), entity.getAccountNumber()),
                firstNonBlank(entity.getCurrency(), "HKD")
        );
    }

    private Optional<DebitAccountSummary> fallback(String profileId) {
        ProfileStore.RuntimeProfile profile = profiles.runtimeProfile(profileId);
        if (blank(profile.debitAccountNumber())) return Optional.empty();
        String accountNumber = profile.debitAccountNumber().trim();
        String productCategoryCode = blank(profile.debitProductCategoryCode())
                ? null
                : profile.debitProductCategoryCode().trim();
        String currency = blank(profile.paymentCurrency()) ? "HKD" : profile.paymentCurrency().trim();
        return Optional.of(new DebitAccountSummary(
                LEGACY_ID_PREFIX + accountNumber,
                accountNumber,
                productCategoryCode,
                legacyDisplayLabel(accountNumber, currency),
                currency
        ));
    }

    private static String legacyDisplayLabel(String accountNumber, String currency) {
        String suffix = accountNumber.length() > 8
                ? accountNumber.substring(Math.max(0, accountNumber.length() - 8))
                : accountNumber;
        return "%s primary account • %s".formatted(currency.toUpperCase(Locale.ROOT), suffix);
    }

    private static String firstNonBlank(String first, String fallback) {
        if (!blank(first)) return first.trim();
        return fallback;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
