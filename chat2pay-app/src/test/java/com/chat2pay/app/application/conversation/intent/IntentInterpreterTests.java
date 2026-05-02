package com.chat2pay.app.application.conversation.intent;

import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.application.capability.CapabilityRegistry;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.integration.llm.LlmCompletionRequest;
import com.chat2pay.app.integration.llm.LlmCompletionResponse;
import com.chat2pay.app.integration.llm.LlmCompletionResponse.ToolCall;
import com.chat2pay.app.integration.llm.LlmProvider;
import com.chat2pay.app.integration.llm.LlmSelection;
import com.chat2pay.app.integration.llm.LlmRouter;
import com.chat2pay.app.integration.llm.LlmUseCase;
import com.chat2pay.app.persistence.repository.PayeeStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentInterpreterTests {

    private final LlmRouter router = mock(LlmRouter.class);
    private final PayeeStore payees = mock(PayeeStore.class);
    private final Chat2PayProperties properties = new Chat2PayProperties(
            "COPILOT_PERSONAL",
            "REMOTE_API",
            "HKD",
            new Chat2PayProperties.IntentProperties(true, 350, 0.0),
            null,
            null,
            null
    );

    @Test
    void usesLlmToolDecisionWhenProviderIsAvailable() {
        LlmProvider provider = mock(LlmProvider.class);
        LocalDate paymentDate = LocalDate.now().plusDays(1);
        when(provider.providerType()).thenReturn(LlmProviderType.COPILOT_PERSONAL);
        when(provider.complete(any(LlmCompletionRequest.class))).thenReturn(new LlmCompletionResponse(
                LlmProviderType.COPILOT_PERSONAL,
                "gpt-5.4",
                """
                {
                  "intent": "DOMESTIC_PAYMENT",
                  "toolName": "prepare_domestic_payment",
                  "payeeQuery": "Sarah Wong",
                  "amount": 88.5,
                  "paymentDate": "%s"
                }
                """.formatted(paymentDate)
        ));
        when(router.select(LlmUseCase.INTENT))
                .thenReturn(Optional.of(new LlmSelection(provider, "Qwen3-32B-AWQ")));
        when(payees.findAliasInText("profile_1", "send 88.5 to sarah tomorrow")).thenReturn("sarah");

        IntentAnalysis analysis = interpreter().analyze(session(ConversationState.IDLE), "send 88.5 to sarah tomorrow", "profile_1");

        assertThat(analysis.intent()).isEqualTo(IntentType.DOMESTIC_PAYMENT);
        assertThat(analysis.toolName()).isEqualTo("prepare_domestic_payment");
        assertThat(analysis.payeeQuery()).isEqualTo("sarah wong");
        assertThat(analysis.amount()).isEqualByComparingTo(new BigDecimal("88.5"));
        assertThat(analysis.paymentDate()).isEqualTo(paymentDate);
        assertThat(analysis.source()).isEqualTo("LLM:COPILOT_PERSONAL");
        ArgumentCaptor<LlmCompletionRequest> request = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(provider).complete(request.capture());
        assertThat(request.getValue().model()).isEqualTo("Qwen3-32B-AWQ");
    }

    @Test
    void usesProviderToolCallWhenAvailable() {
        LlmProvider provider = mock(LlmProvider.class);
        LocalDate paymentDate = LocalDate.now().plusDays(1);
        when(provider.providerType()).thenReturn(LlmProviderType.COPILOT_PERSONAL);
        when(provider.complete(any(LlmCompletionRequest.class))).thenReturn(new LlmCompletionResponse(
                LlmProviderType.COPILOT_PERSONAL,
                "gpt-5.4",
                "",
                List.of(new ToolCall(
                        "call_1",
                        "prepare_domestic_payment",
                        """
                        {"payeeQuery":"Sarah Wong","amount":88.5,"paymentDate":"%s"}
                        """.formatted(paymentDate)
                ))
        ));
        when(router.select(LlmUseCase.INTENT))
                .thenReturn(Optional.of(new LlmSelection(provider, null)));

        IntentAnalysis analysis = interpreter().analyze(session(ConversationState.IDLE), "send 88.5 to sarah tomorrow", "profile_1");

        assertThat(analysis.intent()).isEqualTo(IntentType.DOMESTIC_PAYMENT);
        assertThat(analysis.toolName()).isEqualTo("prepare_domestic_payment");
        assertThat(analysis.payeeQuery()).isEqualTo("sarah wong");
        assertThat(analysis.amount()).isEqualByComparingTo(new BigDecimal("88.5"));
        assertThat(analysis.paymentDate()).isEqualTo(paymentDate);
    }

    @Test
    void fallsBackToLocalParserWhenNoProviderIsAvailable() {
        when(router.select(LlmUseCase.INTENT)).thenReturn(Optional.empty());
        when(payees.findAliasInText("profile_1", "do i have bob registered?")).thenReturn("bob");

        IntentAnalysis analysis = interpreter().analyze(session(ConversationState.IDLE), "do i have bob registered?", "profile_1");

        assertThat(analysis.intent()).isEqualTo(IntentType.PAYEE_LOOKUP);
        assertThat(analysis.toolName()).isEqualTo("get_registered_payees");
        assertThat(analysis.payeeQuery()).isEqualTo("bob");
        assertThat(analysis.source()).isEqualTo("REGEX_FALLBACK");
    }

    @Test
    void classifiesCrossBorderAsUnsupportedV1Capability() {
        when(router.select(LlmUseCase.INTENT)).thenReturn(Optional.empty());

        IntentAnalysis analysis = interpreter().analyze(
                session(ConversationState.IDLE),
                "send a cross-border wire payment to alice",
                "profile_1");

        assertThat(analysis.intent()).isEqualTo(IntentType.CROSS_BORDER_PAYMENT);
        assertThat(analysis.toolName()).isEqualTo("unsupported_cross_border_payment");
        assertThat(analysis.source()).isEqualTo("REGEX_FALLBACK");
    }

    private IntentInterpreter interpreter() {
        return new IntentInterpreter(router, properties, new ObjectMapper(), payees, new CapabilityRegistry());
    }

    private ChatSessionDetail session(ConversationState state) {
        Instant now = Instant.now();
        return new ChatSessionDetail(
                "session_test",
                "Test",
                false,
                ChatSessionStatus.ACTIVE,
                state,
                LlmProviderType.COPILOT_PERSONAL,
                null,
                now,
                now
        );
    }
}
