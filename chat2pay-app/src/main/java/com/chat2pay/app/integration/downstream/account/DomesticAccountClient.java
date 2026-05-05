package com.chat2pay.app.integration.downstream.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Downstream debit/source-account directory used before domestic-payment confirmation.
 * Accounts are grouped by master/integrated account; only sub-accounts are selectable.
 */
public interface DomesticAccountClient {

    List<AccountGroup> loadAccounts(String profileId);

    default List<SelectableAccount> listSelectableAccounts(String profileId) {
        return loadAccounts(profileId).stream()
                .flatMap(group -> group.subAccounts().stream())
                .toList();
    }

    default Optional<SelectableAccount> findSelectableAccount(String profileId, String accountId) {
        if (accountId == null || accountId.isBlank()) return Optional.empty();
        return listSelectableAccounts(profileId).stream()
                .filter(account -> accountId.equals(account.accountId()))
                .findFirst();
    }

    record AccountGroup(
            String groupId,
            ParentAccount parentAccount,
            List<SelectableAccount> subAccounts
    ) {
        public AccountGroup {
            subAccounts = subAccounts == null ? List.of() : List.copyOf(subAccounts);
        }
    }

    record ParentAccount(
            String accountId,
            String accountDisplay,
            String productDescription
    ) {}

    record SelectableAccount(
            String accountId,
            String parentAccountId,
            String accountDisplay,
            String productCategoryCode,
            String productDescription,
            String displayLabel,
            String currency,
            String ledgerBalanceIndicator,
            BigDecimal ledgerBalanceAmount,
            String ledgerBalanceCurrency
    ) {}
}
