package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.DebitAccountSummary;
import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.application.conversation.intent.IntentAnalysis;
import com.chat2pay.app.application.conversation.intent.IntentType;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.domain.payment.PaymentType;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient.PaymentConfirmationResult;
import com.chat2pay.app.persistence.repository.PayeeStore;
import com.chat2pay.app.persistence.repository.PayeeStore.PayeeAccountRef;
import com.chat2pay.app.persistence.repository.PayeeStore.RegisteredAccount;
import com.chat2pay.app.persistence.repository.PayeeStore.RegisteredPayee;
import com.chat2pay.app.persistence.repository.ProfileDebitAccountStore;
import com.chat2pay.app.persistence.repository.ProfileStore;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomesticPaymentJourneyServiceTests {

    private final PayeeStore payees = mock(PayeeStore.class);
    private final DomesticPaymentClient domesticPayments = mock(DomesticPaymentClient.class);
    private final ProfileDebitAccountStore debitAccounts = mock(ProfileDebitAccountStore.class);
    private final ProfileStore profiles = mock(ProfileStore.class);

    @Test
    void domesticExecutionDirectlyConfirmsValidatedDraft() {
        SessionRecord record = recordWithDraft();
        when(domesticPayments.confirm(org.mockito.ArgumentMatchers.any())).thenReturn(new PaymentConfirmationResult(
                true,
                "DOM-REF-1",
                200,
                Map.of("ok", true),
                "confirmed"
        ));

        ChatMessage response = service().executePayment(record);

        ArgumentCaptor<DomesticPaymentClient.DomesticPaymentRequest> request =
                ArgumentCaptor.forClass(DomesticPaymentClient.DomesticPaymentRequest.class);
        verify(domesticPayments).confirm(request.capture());
        assertThat(request.getValue().profileId()).isEqualTo("profile_1");
        assertThat(request.getValue().payeeIdIndex()).isEqualTo("payee_1");
        assertThat(request.getValue().selectedDebitAccount().accountId()).isEqualTo("acct_primary");
        assertThat(request.getValue().amount()).isEqualByComparingTo(new BigDecimal("125.50"));
        assertThat(record.session().activeDraft().status()).isEqualTo(PaymentDraftStatus.CONFIRMED);
        assertThat(record.session().activeDraft().downstreamReference()).isEqualTo("DOM-REF-1");
        ContentBlock.InfoCardBlock block = (ContentBlock.InfoCardBlock) response.contentBlocks().get(0);
        assertThat(block.title()).contains("Payment submitted");
    }

    @Test
    void fillingAmountAfterPayeeSelectionDoesNotAskToSelectPayeeAgain() {
        PaymentDraft draft = draft(selectedPayee(), null, null);
        SessionRecord record = recordWithDraft(draft, ConversationState.COLLECTING_DETAILS);

        ChatMessage response = service().continueDomesticPayment(record, domesticIntent(
                null, new BigDecimal("100"), null));

        verify(payees, never()).findByQuery(anyString(), anyString());
        assertThat(record.session().activeDraft().selectedPayee().payeeId()).isEqualTo("payee_1");
        assertThat(record.session().activeDraft().amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(record.session().activeDraft().paymentDate()).isNull();
        assertThat(record.session().state()).isEqualTo(ConversationState.COLLECTING_DETAILS);
        ContentBlock.TextBlock block = (ContentBlock.TextBlock) response.contentBlocks().get(0);
        assertThat(block.text()).contains("payment date").doesNotContain("amount, payment date");
    }

    @Test
    void fillingDateAfterPayeeSelectionKeepsPayeeAndAsksForAmount() {
        LocalDate date = LocalDate.now().plusDays(1);
        PaymentDraft draft = draft(selectedPayee(), null, null);
        SessionRecord record = recordWithDraft(draft, ConversationState.COLLECTING_DETAILS);

        ChatMessage response = service().continueDomesticPayment(record, domesticIntent(null, null, date));

        verify(payees, never()).findByQuery(anyString(), anyString());
        assertThat(record.session().activeDraft().selectedPayee().payeeId()).isEqualTo("payee_1");
        assertThat(record.session().activeDraft().amount()).isNull();
        assertThat(record.session().activeDraft().paymentDate()).isEqualTo(date);
        ContentBlock.TextBlock block = (ContentBlock.TextBlock) response.contentBlocks().get(0);
        assertThat(block.text()).contains("amount").doesNotContain("payment date");
    }

    @Test
    void changingPayeeClearsAmountAndDate() {
        PaymentDraft draft = draft(selectedPayee(), new BigDecimal("125.50"), LocalDate.now());
        SessionRecord record = recordWithDraft(draft, ConversationState.COLLECTING_DETAILS);
        RegisteredPayee bob = registeredPayee("contact_2", "Bob Lee",
                List.of(localAccount("payee_2", "Bob Lee", "Test Bank", "998877", "Savings")));
        when(payees.findByQuery("profile_1", "bob")).thenReturn(List.of(bob));
        when(payees.findAccount("profile_1", "payee_2"))
                .thenReturn(Optional.of(new PayeeAccountRef(bob, bob.accounts().get(0))));

        ChatMessage response = service().continueDomesticPayment(record, domesticIntent(
                "bob", new BigDecimal("200"), LocalDate.now().plusDays(1)));

        verify(payees).findByQuery("profile_1", "bob");
        assertThat(record.session().activeDraft().selectedPayee().payeeId()).isEqualTo("payee_2");
        assertThat(record.session().activeDraft().amount()).isNull();
        assertThat(record.session().activeDraft().paymentDate()).isNull();
        ContentBlock.TextBlock block = (ContentBlock.TextBlock) response.contentBlocks().get(0);
        assertThat(block.text()).contains("amount", "payment date");
    }

    @Test
    void registeredPayeeLookupCarriesCardMetadataForTheUi() {
        SessionRecord record = recordWithDraft(draft(null, null, null), ConversationState.IDLE);
        when(payees.all("profile_1")).thenReturn(List.of(
                registeredPayee("contact_1", "Alice Chan",
                        List.of(localAccount("payee_1", "Alice Chan", "Test Bank", "123456", "Current"))),
                registeredPayee("contact_2", "Bob Lee",
                        List.of(localAccount("payee_2", "Bob Lee", "Test Bank", "998877", "Savings")))
        ));

        ChatMessage response = service().handlePayeeLookup(record, null);

        ContentBlock.SummaryCardBlock block = (ContentBlock.SummaryCardBlock) response.contentBlocks().get(1);
        assertThat(block.metadata()).containsEntry("purpose", "registered-payee-results");
        assertThat(block.metadata()).containsEntry("payeeCount", 2);
        assertThat(block.metadata()).containsEntry("accountCount", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> renderedPayees = (List<Map<String, Object>>) block.metadata().get("payees");
        assertThat(renderedPayees).hasSize(2);
        assertThat(renderedPayees.get(0)).containsEntry("nickName", "Alice Chan");
        assertThat(renderedPayees.get(0)).containsEntry("accountCount", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstPayeeAccounts =
                (List<Map<String, Object>>) renderedPayees.get(0).get("accounts");
        assertThat(firstPayeeAccounts).hasSize(1);
        assertThat(firstPayeeAccounts.get(0)).containsEntry("addressId", "payee_1");
        assertThat(firstPayeeAccounts.get(0)).containsEntry("bankName", "Test Bank");
        assertThat(firstPayeeAccounts.get(0)).containsEntry("payeeAccountLabel", "Local");
        assertThat(firstPayeeAccounts.get(0)).containsEntry("selectable", true);
    }

    @Test
    void confirmationSummaryAdvertisesEditablePaymentDateForTheUiDatePicker() {
        PaymentDraft draft = draft(selectedPayee(), new BigDecimal("125.50"), LocalDate.now().plusDays(1));
        SessionRecord record = recordWithDraft(draft, ConversationState.COLLECTING_DETAILS);
        when(debitAccounts.list("profile_1")).thenReturn(List.of(primaryDebitAccount()));

        ChatMessage response = service().continueDomesticPayment(record, domesticIntent(null, null, null));

        ContentBlock.SummaryCardBlock block = (ContentBlock.SummaryCardBlock) response.contentBlocks().get(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> editableFields = (List<Map<String, Object>>) block.metadata().get("editableFields");
        assertThat(editableFields).hasSize(1);
        assertThat(editableFields.get(0)).containsEntry("label", "Payment date");
        assertThat(editableFields.get(0)).containsEntry("fieldId", "paymentDate");
        assertThat(editableFields.get(0)).containsEntry("fieldType", "DATE");
        assertThat(editableFields.get(0)).containsEntry("value", LocalDate.now().plusDays(1).toString());
    }

    @Test
    void updatePaymentDateMutatesActiveDraftWithoutEmittingMessage() {
        PaymentDraft draft = draft(selectedPayee(), new BigDecimal("125.50"), LocalDate.now().plusDays(1));
        SessionRecord record = recordWithDraft(draft, ConversationState.AWAITING_CONFIRMATION);
        LocalDate picked = LocalDate.now().plusDays(3);

        service().updatePaymentDate(record, picked);

        assertThat(record.session().activeDraft().paymentDate()).isEqualTo(picked);
    }

    @Test
    void payeeSelectionCarriesStructuredPayeeMetadata() {
        SessionRecord record = recordWithDraft(draft(null, null, null), ConversationState.COLLECTING_DETAILS);
        when(payees.findByQuery("profile_1", "alice")).thenReturn(List.of(
                registeredPayee("contact_1", "Alice Chan",
                        List.of(localAccount("payee_1", "Alice Chan", "Test Bank", "123456", "Current"))),
                registeredPayee("contact_2", "Alice Savings",
                        List.of(localAccount("payee_2", "Alice Savings", "Second Bank", "223344", "Savings")))
        ));

        ChatMessage response = service().continueDomesticPayment(record, domesticIntent("alice", null, null));

        ContentBlock.SelectableListBlock block = (ContentBlock.SelectableListBlock) response.contentBlocks().get(1);
        assertThat(block.metadata()).containsEntry("purpose", "payee-account-selection");
        assertThat(block.metadata()).containsEntry("payeeCount", 2);
        assertThat(block.items()).hasSize(2);
        assertThat(block.items().get(0).itemId()).isEqualTo("payee_1");
        assertThat(block.items().get(0).metadata()).containsEntry("addressId", "payee_1");
        assertThat(block.items().get(0).metadata()).containsEntry("accountNumber", "123456");
        assertThat(block.items().get(0).metadata()).containsEntry("payeeAccountLabel", "Local");
    }

    @Test
    void selectingInternationalAccountRejectsCrossBorderPayment() {
        PaymentDraft draft = draft(null, null, null);
        SessionRecord record = recordWithDraft(draft, ConversationState.AWAITING_PAYEE_SELECTION);
        RegisteredAccount intlAccount = new RegisteredAccount(
                "address_intl_1",
                "2",
                "004",
                "Test Bank",
                "999000111",
                "USD Savings",
                "USDAVSAV",
                "International",
                new BigDecimal("100000"),
                "USD",
                "USD"
        );
        RegisteredPayee payee = registeredPayee("contact_x", "Lisa Cheung", List.of(intlAccount));
        when(payees.findAccount("profile_1", "address_intl_1"))
                .thenReturn(Optional.of(new PayeeAccountRef(payee, intlAccount)));

        ChatMessage response = service().selectPayee(record, "address_intl_1");

        assertThat(record.session().activeDraft().selectedPayee()).isNull();
        ContentBlock.InfoCardBlock block = (ContentBlock.InfoCardBlock) response.contentBlocks().get(0);
        assertThat(block.title()).isEqualTo("Cross-border payment not supported");
    }

    @Test
    void completeDraftWithMultipleDebitAccountsAsksForAccountSelectionBeforeConfirmation() {
        PaymentDraft draft = draft(selectedPayee(), new BigDecimal("125.50"), LocalDate.now().plusDays(1), null);
        SessionRecord record = recordWithDraft(draft, ConversationState.COLLECTING_DETAILS);
        when(debitAccounts.list("profile_1")).thenReturn(List.of(
                primaryDebitAccount(),
                secondaryDebitAccount()
        ));

        ChatMessage response = service().continueDomesticPayment(record, domesticIntent(null, null, null));

        assertThat(record.session().state()).isEqualTo(ConversationState.AWAITING_DEBIT_ACCOUNT_SELECTION);
        ContentBlock.SelectableListBlock block = (ContentBlock.SelectableListBlock) response.contentBlocks().get(1);
        assertThat(block.metadata()).containsEntry("purpose", "debit-account-selection");
        assertThat(block.items()).hasSize(2);
    }

    private DomesticPaymentJourneyService service() {
        return new DomesticPaymentJourneyService(
                payees,
                domesticPayments,
                debitAccounts,
                profiles,
                new ChatBlockFactory(),
                new ConversationStateMachine(),
                new PaymentPolicyGuard()
        );
    }

    private SessionRecord recordWithDraft() {
        return recordWithDraft(sessionWithDraft().activeDraft(), ConversationState.AWAITING_CONFIRMATION);
    }

    private SessionRecord recordWithDraft(PaymentDraft draft, ConversationState state) {
        SessionRecord record = mock(SessionRecord.class);
        AtomicReference<ChatSessionDetail> session = new AtomicReference<>(sessionWithDraft(draft, state));
        when(record.profileId()).thenReturn("profile_1");
        when(record.session()).thenAnswer(invocation -> session.get());
        doAnswer(invocation -> {
            session.set(invocation.getArgument(0));
            return null;
        }).when(record).setSession(org.mockito.ArgumentMatchers.any());
        return record;
    }

    private ChatSessionDetail sessionWithDraft() {
        return sessionWithDraft(draft(selectedPayee(), new BigDecimal("125.50"), LocalDate.now(), primaryDebitAccount()),
                ConversationState.AWAITING_CONFIRMATION);
    }

    private ChatSessionDetail sessionWithDraft(PaymentDraft draft, ConversationState state) {
        Instant now = Instant.now();
        return new ChatSessionDetail(
                "session_1",
                "Pay Alice",
                false,
                ChatSessionStatus.ACTIVE,
                state,
                LlmProviderType.COPILOT_PERSONAL,
                draft,
                now,
                now
        );
    }

    private PaymentDraft draft(PayeeSummary payee, BigDecimal amount, LocalDate date) {
        return draft(payee, amount, date, primaryDebitAccount());
    }

    private PaymentDraft draft(PayeeSummary payee, BigDecimal amount, LocalDate date,
                               DebitAccountSummary selectedDebitAccount) {
        return new PaymentDraft(
                "draft_1",
                "session_1",
                PaymentType.DOMESTIC_PAYMENT,
                PaymentDraftStatus.DRAFT,
                "alice",
                payee,
                selectedDebitAccount,
                amount,
                "HKD",
                date,
                null,
                null,
                Map.of(),
                Instant.now()
        );
    }

    private PayeeSummary selectedPayee() {
        return new PayeeSummary(
                "payee_1",
                "Alice Chan",
                "DOMESTIC",
                "004",
                "Test Bank",
                "123456",
                "Current - 123456"
        );
    }

    private DebitAccountSummary primaryDebitAccount() {
        return new DebitAccountSummary(
                "acct_primary",
                "123-000-001",
                "CUR",
                "HKD primary account • 123-000-001",
                "HKD"
        );
    }

    private DebitAccountSummary secondaryDebitAccount() {
        return new DebitAccountSummary(
                "acct_secondary",
                "123-000-002",
                "SAV",
                "HKD savings account • 123-000-002",
                "HKD"
        );
    }

    private PayeeSummary payee(String id, String name, String bankName, String displayLabel) {
        return new PayeeSummary(
                id,
                name,
                "DOMESTIC",
                "004",
                bankName,
                "998877",
                displayLabel
        );
    }

    private RegisteredPayee registeredPayee(String contactId, String displayName, List<RegisteredAccount> accounts) {
        return new RegisteredPayee(contactId, displayName, displayName, List.of(displayName.toLowerCase()), accounts);
    }

    private RegisteredAccount localAccount(String addressId, String name, String bankName,
                                           String accountNumber, String accountProductType) {
        return new RegisteredAccount(
                addressId,
                "2",
                "004",
                bankName,
                accountNumber,
                accountProductType,
                accountProductType.toUpperCase(),
                "Local",
                new BigDecimal("10000"),
                "HKD",
                "HKD"
        );
    }

    private IntentAnalysis domesticIntent(String payeeQuery, BigDecimal amount, LocalDate date) {
        return new IntentAnalysis(
                IntentType.DOMESTIC_PAYMENT,
                IntentAnalysis.toolNameFor(IntentType.DOMESTIC_PAYMENT),
                payeeQuery,
                amount,
                date,
                "TEST"
        );
    }
}
