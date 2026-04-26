package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.domain.payment.PaymentType;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient;
import com.chat2pay.app.integration.downstream.domestic.DomesticPaymentClient.PaymentConfirmationResult;
import com.chat2pay.app.persistence.repository.PayeeStore;
import com.chat2pay.app.persistence.repository.ProfileStore;
import com.chat2pay.app.persistence.repository.SessionStore.SessionRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomesticPaymentJourneyServiceTests {

    private final PayeeStore payees = mock(PayeeStore.class);
    private final DomesticPaymentClient domesticPayments = mock(DomesticPaymentClient.class);
    private final ProfileStore profiles = mock(ProfileStore.class);
    private final Chat2PayProperties properties = new Chat2PayProperties(
            "COPILOT_PERSONAL",
            "REMOTE_API",
            "HKD",
            new Chat2PayProperties.IntentProperties(true, 350, 0.0),
            null
    );

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
        assertThat(request.getValue().amount()).isEqualByComparingTo(new BigDecimal("125.50"));
        assertThat(record.session().activeDraft().status()).isEqualTo(PaymentDraftStatus.CONFIRMED);
        assertThat(record.session().activeDraft().downstreamReference()).isEqualTo("DOM-REF-1");
        ContentBlock.InfoCardBlock block = (ContentBlock.InfoCardBlock) response.contentBlocks().get(0);
        assertThat(block.title()).contains("Payment submitted");
    }

    private DomesticPaymentJourneyService service() {
        return new DomesticPaymentJourneyService(
                payees,
                domesticPayments,
                properties,
                profiles,
                new ChatBlockFactory(),
                new ConversationStateMachine(),
                new PaymentPolicyGuard()
        );
    }

    private SessionRecord recordWithDraft() {
        SessionRecord record = mock(SessionRecord.class);
        AtomicReference<ChatSessionDetail> session = new AtomicReference<>(sessionWithDraft());
        when(record.profileId()).thenReturn("profile_1");
        when(record.session()).thenAnswer(invocation -> session.get());
        doAnswer(invocation -> {
            session.set(invocation.getArgument(0));
            return null;
        }).when(record).setSession(org.mockito.ArgumentMatchers.any());
        return record;
    }

    private ChatSessionDetail sessionWithDraft() {
        Instant now = Instant.now();
        PayeeSummary payee = new PayeeSummary(
                "payee_1",
                "Alice Chan",
                "DOMESTIC",
                "004",
                "Test Bank",
                "123456",
                "Current - 123456"
        );
        PaymentDraft draft = new PaymentDraft(
                "draft_1",
                "session_1",
                PaymentType.DOMESTIC_PAYMENT,
                PaymentDraftStatus.AWAITING_CONFIRMATION,
                "alice",
                payee,
                new BigDecimal("125.50"),
                "HKD",
                LocalDate.now(),
                null,
                null,
                Map.of(),
                now
        );
        return new ChatSessionDetail(
                "session_1",
                "Pay Alice",
                false,
                ChatSessionStatus.ACTIVE,
                ConversationState.AWAITING_CONFIRMATION,
                LlmProviderType.COPILOT_PERSONAL,
                draft,
                now,
                now
        );
    }
}
