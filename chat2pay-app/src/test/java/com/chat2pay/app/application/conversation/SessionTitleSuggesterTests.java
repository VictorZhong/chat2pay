package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.domain.conversation.MessageKind;
import com.chat2pay.app.domain.conversation.MessageRole;
import com.chat2pay.app.integration.llm.LlmCompletionRequest;
import com.chat2pay.app.integration.llm.LlmCompletionResponse;
import com.chat2pay.app.integration.llm.LlmProvider;
import com.chat2pay.app.integration.llm.LlmSelection;
import com.chat2pay.app.integration.llm.LlmRouter;
import com.chat2pay.app.integration.llm.LlmUseCase;
import com.chat2pay.app.persistence.repository.SessionStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTitleSuggesterTests {

    private final LlmRouter router = mock(LlmRouter.class);
    private final SessionStore sessions = mock(SessionStore.class);

    @Test
    void usesTitleUseCaseModelOverride() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.providerType()).thenReturn(LlmProviderType.REMOTE_API);
        when(provider.complete(any(LlmCompletionRequest.class))).thenReturn(new LlmCompletionResponse(
                LlmProviderType.REMOTE_API,
                "Qwen3-32B-AWQ",
                "Pay Sarah Tomorrow",
                List.of(),
                "stop",
                "internal reasoning"
        ));
        when(router.select(LlmUseCase.TITLE))
                .thenReturn(Optional.of(new LlmSelection(provider, "Qwen3-32B-AWQ")));

        suggester().suggestIfEligible(
                "profile_1",
                "session_1",
                "New conversation",
                false,
                List.of(
                        message("m1", MessageRole.USER, "find sarah"),
                        message("m2", MessageRole.ASSISTANT, "I found Sarah Wong."),
                        message("m3", MessageRole.USER, "pay her tomorrow")
                )
        );

        ArgumentCaptor<LlmCompletionRequest> request = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(provider).complete(request.capture());
        assertThat(request.getValue().model()).isEqualTo("Qwen3-32B-AWQ");
        verify(router, never()).providerIfAvailable(LlmProviderType.COPILOT_PERSONAL);
        verify(sessions).applyAiSuggestedTitle("profile_1", "session_1", "Pay Sarah Tomorrow");
    }

    @Test
    void fallsBackToPersonalCopilotWhenRemoteTitleResponseIsTruncated() {
        LlmProvider remote = mock(LlmProvider.class);
        LlmProvider copilot = mock(LlmProvider.class);
        when(remote.providerType()).thenReturn(LlmProviderType.REMOTE_API);
        when(copilot.providerType()).thenReturn(LlmProviderType.COPILOT_PERSONAL);
        when(remote.complete(any(LlmCompletionRequest.class))).thenReturn(new LlmCompletionResponse(
                LlmProviderType.REMOTE_API,
                "Qwen3-32B-AWQ",
                "<think>unfinished",
                List.of(),
                "length",
                "reasoning"
        ));
        when(copilot.complete(any(LlmCompletionRequest.class))).thenReturn(new LlmCompletionResponse(
                LlmProviderType.COPILOT_PERSONAL,
                "gpt-5.4",
                "Pay Sarah Tomorrow",
                List.of(),
                "stop",
                null
        ));
        when(router.select(LlmUseCase.TITLE))
                .thenReturn(Optional.of(new LlmSelection(remote, "Qwen3-32B-AWQ")));
        when(router.providerIfAvailable(LlmProviderType.COPILOT_PERSONAL))
                .thenReturn(Optional.of(copilot));

        suggester().suggestIfEligible(
                "profile_1",
                "session_1",
                "New conversation",
                false,
                List.of(
                        message("m1", MessageRole.USER, "find sarah"),
                        message("m2", MessageRole.ASSISTANT, "I found Sarah Wong."),
                        message("m3", MessageRole.USER, "pay her tomorrow")
                )
        );

        ArgumentCaptor<LlmCompletionRequest> remoteRequest = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        ArgumentCaptor<LlmCompletionRequest> copilotRequest = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        verify(remote).complete(remoteRequest.capture());
        verify(copilot).complete(copilotRequest.capture());
        assertThat(remoteRequest.getValue().model()).isEqualTo("Qwen3-32B-AWQ");
        assertThat(copilotRequest.getValue().model()).isNull();
        verify(sessions).applyAiSuggestedTitle("profile_1", "session_1", "Pay Sarah Tomorrow");
    }

    private SessionTitleSuggester suggester() {
        return new SessionTitleSuggester(router, sessions);
    }

    private ChatMessage message(String id, MessageRole role, String text) {
        return new ChatMessage(
                id,
                "session_1",
                role,
                MessageKind.TEXT,
                text,
                null,
                null,
                Instant.now()
        );
    }
}
