package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatTurnResponse;
import com.chat2pay.app.api.dto.ChatDtos.SendMessageRequest;
import com.chat2pay.app.api.dto.ChatDtos.UiEventRequest;
import com.chat2pay.app.api.dto.ContentBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps the synchronous orchestrator into an SSE-style event stream that
 * matches the frontend's TurnStreamEvent contract:
 *   user-message -> assistant-message-start -> assistant-message-delta(*) -> assistant-message-complete
 * On exceptions a single turn-error event is emitted.
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private static final long TIMEOUT_MS = 60_000L;
    private static final int CHUNK_SIZE = 32;
    private static final long DELTA_PAUSE_MS = 30L;
    private static final int MAX_STREAM_THREADS = 16;
    private static final int STREAM_QUEUE_CAPACITY = 32;

    private final ChatOrchestratorService orchestrator;
    private final Executor executor = new ThreadPoolExecutor(
            MAX_STREAM_THREADS,
            MAX_STREAM_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(STREAM_QUEUE_CAPACITY),
            chatThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    public ChatStreamService(ChatOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    public SseEmitter streamUserMessage(String profileId, String sessionId, SendMessageRequest request) {
        return run(() -> orchestrator.handleUserMessage(profileId, sessionId, request));
    }

    public SseEmitter streamUiEvent(String profileId, String sessionId, UiEventRequest request) {
        return run(() -> orchestrator.handleUiEvent(profileId, sessionId, request));
    }

    private SseEmitter run(java.util.function.Supplier<ChatTurnResponse> work) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        try {
            executor.execute(() -> {
            long startedNanos = System.nanoTime();
            try {
                ChatTurnResponse turn = work.get();
                emit(emitter, "user-message", Map.of("message", turn.userMessage()));

                ChatMessage assistant = turn.assistantMessage();
                emit(emitter, "assistant-message-start", Map.of(
                        "messageId", assistant.messageId(),
                        "sessionId", assistant.sessionId()
                ));

                String leadingText = leadingText(assistant);
                if (leadingText != null && !leadingText.isEmpty()) {
                    for (int i = 0; i < leadingText.length(); i += CHUNK_SIZE) {
                        String slice = leadingText.substring(i, Math.min(leadingText.length(), i + CHUNK_SIZE));
                        emit(emitter, "assistant-message-delta", Map.of(
                                "messageId", assistant.messageId(),
                                "textDelta", slice
                        ));
                        sleep();
                    }
                }
                if (assistant.contentBlocks() != null) {
                    boolean firstSkipped = false;
                    for (ContentBlock block : assistant.contentBlocks()) {
                        if (!firstSkipped && leadingText != null && !leadingText.isEmpty()
                                && (block instanceof ContentBlock.TextBlock
                                || block instanceof ContentBlock.InfoCardBlock
                                || block instanceof ContentBlock.ErrorCardBlock)) {
                            firstSkipped = true;
                            continue;
                        }
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("messageId", assistant.messageId());
                        payload.put("block", block);
                        emit(emitter, "assistant-message-delta", payload);
                        sleep();
                    }
                }

                emit(emitter, "assistant-message-complete", Map.of(
                        "message", assistant,
                        "session", turn.session()
                ));
                emitter.complete();
            } catch (Throwable ex) {
                long elapsedMs = Math.max(0, Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
                log.warn("Chat SSE turn failed after {} ms: {}", elapsedMs, ex.getMessage());
                log.debug("Chat SSE turn failure details", ex);
                try {
                    emit(emitter, "turn-error", Map.of(
                            "code", ex.getClass().getSimpleName(),
                            "message", ex.getMessage() != null ? ex.getMessage() : "Unhandled error",
                            "processingMs", elapsedMs
                    ));
                    emitter.complete();
                } catch (IOException ioEx) {
                    log.debug("Chat SSE turn-error event could not be written", ioEx);
                    emitter.complete();
                }
            }
            });
        } catch (RejectedExecutionException ex) {
            log.warn("Chat SSE turn rejected because the bounded worker pool is full: {}", ex.getMessage());
            try {
                emit(emitter, "turn-error", Map.of(
                        "code", "CHAT_STREAM_BUSY",
                        "message", "Chat service is busy. Please retry in a moment.",
                        "processingMs", 0
                ));
                emitter.complete();
            } catch (IOException ioEx) {
                log.debug("Chat SSE rejection event could not be written", ioEx);
                emitter.completeWithError(ioEx);
            }
        }
        return emitter;
    }

    private static ThreadFactory chatThreadFactory() {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread t = new Thread(runnable, "chat-sse-worker-" + sequence.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    private static void emit(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private static String leadingText(ChatMessage message) {
        if (message.contentBlocks() == null || message.contentBlocks().isEmpty()) return null;
        ContentBlock first = message.contentBlocks().get(0);
        if (first instanceof ContentBlock.TextBlock t) return t.text();
        if (first instanceof ContentBlock.InfoCardBlock i) return i.text();
        if (first instanceof ContentBlock.ErrorCardBlock e) return e.text();
        return null;
    }

    private static void sleep() {
        try { Thread.sleep(DELTA_PAUSE_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
