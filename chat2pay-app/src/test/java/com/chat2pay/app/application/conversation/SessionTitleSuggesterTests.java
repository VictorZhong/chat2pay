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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionTitleSuggesterTests {

    private final LlmRouter router = mock(LlmRouter.class);
    private final SessionStore sessions = mock(SessionStore.class);

    @Test
    void usesTitleUseCaseModelOverride() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.complete(any(LlmCompletionRequest.class))).thenReturn(new LlmCompletionResponse(
                LlmProviderType.REMOTE_API,
                "Qwen3-32B-AWQ",
                "Pay Sarah Tomorrow"
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
