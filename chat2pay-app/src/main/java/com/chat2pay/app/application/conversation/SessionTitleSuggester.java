package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.domain.conversation.MessageRole;
import com.chat2pay.app.integration.llm.LlmCompletionRequest;
import com.chat2pay.app.integration.llm.LlmCompletionResponse;
import com.chat2pay.app.integration.llm.LlmProvider;
import com.chat2pay.app.integration.llm.LlmSelection;
import com.chat2pay.app.integration.llm.LlmRouter;
import com.chat2pay.app.integration.llm.LlmUseCase;
import com.chat2pay.app.persistence.repository.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Asks the active LLM provider for a short (4–6 word) title that summarises the
 * session, then persists it through {@link SessionStore#applyAiSuggestedTitle}.
 * Manual user renames set the {@code title_locked} flag, which makes this
 * suggestion a no-op.
 *
 * <p>This is intentionally best-effort: if the provider is unavailable or the
 * call fails, we simply leave the existing title in place. The cost is also
 * bounded — we only run it once a session has accumulated enough content to
 * summarise (≥ 2 user messages by default).
 */
@Service
public class SessionTitleSuggester {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleSuggester.class);
    private static final String DEFAULT_TITLE = "New conversation";
    private static final int MIN_USER_MESSAGES_BEFORE_SUGGEST = 2;
    private static final int MAX_TITLE_CHARS = 100;
    private static final String FINISH_REASON_STOP = "stop";
    private static final Set<String> UPPERCASE_TITLE_TOKENS = Set.of(
            "hkd", "usd", "aud", "cad", "chf", "cny", "eur", "gbp", "jpy", "rmb", "fx", "swift", "api", "llm"
    );

    private final LlmRouter router;
    private final SessionStore sessions;

    public SessionTitleSuggester(LlmRouter router, SessionStore sessions) {
        this.router = router;
        this.sessions = sessions;
    }

    public void suggestIfEligible(String profileId,
                                  String sessionId,
                                  String currentTitle,
                                  boolean titleLocked,
                                  List<ChatMessage> messages) {
        if (titleLocked) return;
        if (currentTitle != null && !DEFAULT_TITLE.equals(currentTitle)
                && !currentTitle.startsWith("Find ")
                && !currentTitle.startsWith("Pay ")
                && !"Registered payees".equals(currentTitle)) {
            // Already has a meaningful, non-template title.
            return;
        }
        long userTurnCount = messages.stream().filter(m -> m.role() == MessageRole.USER).count();
        if (userTurnCount < MIN_USER_MESSAGES_BEFORE_SUGGEST) return;

        Optional<LlmSelection> selection = router.select(LlmUseCase.TITLE);
        if (selection.isEmpty()) return;

        try {
            String suggested = ask(selection.get(), messages);
            if (suggested == null) return;
            log.debug("Session title suggestion: profileId={} sessionId={} title=\"{}\"",
                    profileId, sessionId, suggested);
            sessions.applyAiSuggestedTitle(profileId, sessionId, suggested);
        } catch (RuntimeException ex) {
            log.debug("Session title suggestion skipped due to provider error: {}", ex.getMessage());
        }
    }

    private String ask(LlmSelection selection, List<ChatMessage> messages) {
        LlmCompletionRequest baseRequest = titleRequest(messages);
        LlmCompletionRequest request = baseRequest.withModel(selection.modelOverride());
        LlmCompletionResponse response;
        try {
            response = selection.provider().complete(request);
        } catch (RuntimeException ex) {
            if (selection.provider().providerType() != LlmProviderType.REMOTE_API) throw ex;
            log.debug("Remote title suggestion failed; trying Personal Copilot fallback: {}", ex.getMessage());
            return askCopilotFallback(baseRequest);
        }

        String suggested = clean(response.content());
        if (selection.provider().providerType() == LlmProviderType.REMOTE_API
                && !remoteTitleResponseUsable(response, suggested)) {
            log.debug("Remote title suggestion incomplete; falling back to Personal Copilot: model={} finishReason={} contentChars={} reasoningChars={}",
                    response.model(), response.finishReason(),
                    response.content() == null ? 0 : response.content().length(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length());
            return askCopilotFallback(baseRequest);
        }
        return suggested;
    }

    private LlmCompletionRequest titleRequest(List<ChatMessage> messages) {
        List<LlmCompletionRequest.Message> prompt = new ArrayList<>();
        prompt.add(new LlmCompletionRequest.Message(LlmCompletionRequest.Role.SYSTEM,
                """
                You name banking-assistant chat sessions.
                Read the conversation transcript and answer with a SHORT title:
                  - 4 to 8 words
                  - Title Case (e.g. "Pay Bob 500 HKD")
                  - no punctuation other than spaces
                  - no quotes, no trailing period
                Reply with the title only, nothing else.
                """));
        prompt.add(new LlmCompletionRequest.Message(LlmCompletionRequest.Role.USER,
                "Transcript:\n" + transcript(messages)));
        return new LlmCompletionRequest(prompt, null, 0.0, List.of(), null);
    }

    private String askCopilotFallback(LlmCompletionRequest request) {
        Optional<LlmProvider> copilot = router.providerIfAvailable(LlmProviderType.COPILOT_PERSONAL);
        if (copilot.isEmpty()) return null;
        LlmCompletionResponse response = copilot.get().complete(request);
        return clean(response.content());
    }

    private static boolean remoteTitleResponseUsable(LlmCompletionResponse response, String suggested) {
        return suggested != null && FINISH_REASON_STOP.equalsIgnoreCase(trimToEmpty(response.finishReason()));
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String transcript(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, messages.size() - 8);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            String text = textOf(m);
            if (text == null || text.isBlank()) continue;
            sb.append(m.role() == MessageRole.USER ? "User: " : "Assistant: ");
            sb.append(text.length() > 240 ? text.substring(0, 240) : text);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String textOf(ChatMessage m) {
        if (m.text() != null && !m.text().isBlank()) return m.text();
        if (m.contentBlocks() == null || m.contentBlocks().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : m.contentBlocks()) {
            if (b instanceof ContentBlock.TextBlock t) sb.append(t.text()).append(' ');
            else if (b instanceof ContentBlock.InfoCardBlock i) sb.append(i.text()).append(' ');
            else if (b instanceof ContentBlock.ErrorCardBlock e) sb.append(e.text()).append(' ');
            else if (b instanceof ContentBlock.SummaryCardBlock s) sb.append(s.title()).append(' ');
        }
        return sb.toString().trim();
    }

    private static String clean(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().replaceAll("[\"'`.]+$", "").replaceAll("^[\"'`]+", "");
        // First line only.
        int newline = trimmed.indexOf('\n');
        if (newline > 0) trimmed = trimmed.substring(0, newline).trim();
        if (trimmed.length() > MAX_TITLE_CHARS) trimmed = trimmed.substring(0, MAX_TITLE_CHARS).trim();
        trimmed = normalizeTitleTokenCasing(trimmed);
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String normalizeTitleTokenCasing(String value) {
        String[] parts = value.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String normalized = parts[i].toLowerCase(Locale.ROOT);
            if (UPPERCASE_TITLE_TOKENS.contains(normalized)) {
                parts[i] = normalized.toUpperCase(Locale.ROOT);
            }
        }
        return String.join(" ", parts).trim();
    }
}
