package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.ErrorSummary;
import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.persistence.entity.ChatMessageEntity;
import com.chat2pay.app.persistence.entity.ChatSessionEntity;
import com.chat2pay.app.persistence.entity.PaymentDraftEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Session + draft + message persistence. SessionRecord acts as a short-lived
 * snapshot whose mutations write through to PostgreSQL through JPA repositories.
 */
@Repository
public class SessionStore {

    private final ChatSessionRepository sessions;
    private final PaymentDraftRepository drafts;
    private final ChatMessageRepository messages;
    private final ProfileRepository profiles;
    private final TransactionTemplate transactions;

    public SessionStore(ChatSessionRepository sessions,
                        PaymentDraftRepository drafts,
                        ChatMessageRepository messages,
                        ProfileRepository profiles,
                        TransactionTemplate transactions) {
        this.sessions = sessions;
        this.drafts = drafts;
        this.messages = messages;
        this.profiles = profiles;
        this.transactions = transactions;
    }

    public class SessionRecord {
        private final String profileId;
        private ChatSessionDetail snapshot;
        private final WriteThroughMessages writeThroughMessages;

        SessionRecord(String profileId, ChatSessionDetail snapshot, List<ChatMessage> seed) {
            this.profileId = profileId;
            this.snapshot = snapshot;
            this.writeThroughMessages = new WriteThroughMessages(profileId, snapshot.sessionId(), seed);
        }

        public String profileId() { return profileId; }

        public ChatSessionDetail session() { return snapshot; }

        public void setSession(ChatSessionDetail updated) {
            persistSessionAndDraft(profileId, updated);
            this.snapshot = updated;
        }

        public List<ChatMessage> messages() { return writeThroughMessages; }
    }

    private class WriteThroughMessages extends AbstractList<ChatMessage> {
        private final String profileId;
        private final String sessionId;
        private final List<ChatMessage> cache;

        WriteThroughMessages(String profileId, String sessionId, List<ChatMessage> seed) {
            this.profileId = profileId;
            this.sessionId = sessionId;
            this.cache = new ArrayList<>(seed);
        }

        @Override public int size() { return cache.size(); }

        @Override public ChatMessage get(int index) { return cache.get(index); }

        @Override
        public boolean add(ChatMessage message) {
            int sequenceNo = cache.size() + 1;
            insertMessage(profileId, sessionId, sequenceNo, message);
            return cache.add(message);
        }
    }

    public ChatSessionDetail create(String profileId, String title) {
        return transactions.execute(status -> {
            ensureProfileExists(profileId);
            String id = "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Instant now = Instant.now();
            String resolvedTitle = (title == null || title.isBlank()) ? "New conversation" : title;

            ChatSessionEntity entity = new ChatSessionEntity();
            entity.setId(id);
            entity.setProfileId(profileId);
            entity.setTitle(resolvedTitle);
            entity.setStatus(ChatSessionStatus.ACTIVE);
            entity.setState(ConversationState.IDLE);
            entity.setLlmProvider(LlmProviderType.COPILOT_PERSONAL);
            entity.setMessageCount(0);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            sessions.save(entity);

            return mapSession(entity, null);
        });
    }

    public SessionRecord get(String profileId, String sessionId) {
        ChatSessionEntity entity = sessions.findByIdAndProfileId(sessionId, profileId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        PaymentDraft draft = loadDraftBySession(sessionId);
        List<ChatMessage> seed = messages.findBySessionIdOrderBySequenceNoAsc(sessionId).stream()
                .map(this::mapMessage)
                .toList();
        return new SessionRecord(profileId, mapSession(entity, draft), seed);
    }

    public List<ChatSessionDetail> listByProfile(String profileId) {
        return sessions.findByProfileIdOrderByUpdatedAtDesc(profileId).stream()
                .map(s -> mapSession(s, loadDraftBySession(s.getId())))
                .toList();
    }

    private void persistSessionAndDraft(String profileId, ChatSessionDetail detail) {
        transactions.executeWithoutResult(status -> {
            Instant now = Instant.now();
            PaymentDraft draft = detail.activeDraft();
            if (draft != null) saveDraft(profileId, draft, detail.sessionId(), now);

            ChatSessionEntity entity = sessions.findById(detail.sessionId())
                    .orElseThrow(() -> new NoSuchElementException("Session no longer exists: " + detail.sessionId()));
            entity.setTitle(detail.title());
            entity.setStatus(detail.status());
            entity.setState(detail.state());
            entity.setLlmProvider(detail.llmProvider());
            entity.setActiveDraftId(draft == null ? null : draft.draftId());
            entity.setUpdatedAt(now);
            sessions.save(entity);
        });
    }

    private void insertMessage(String profileId, String sessionId, int sequenceNo, ChatMessage message) {
        transactions.executeWithoutResult(status -> {
            ChatMessageEntity entity = new ChatMessageEntity();
            entity.setId(message.messageId());
            entity.setProfileId(profileId);
            entity.setSessionId(sessionId);
            entity.setSequenceNo(sequenceNo);
            entity.setRole(message.role());
            entity.setKind(message.kind());
            entity.setContentText(message.text());
            entity.setContentBlocks(message.contentBlocks());
            entity.setMetadata(message.metadata());
            entity.setCreatedAt(message.createdAt() == null ? Instant.now() : message.createdAt());
            messages.save(entity);

            ChatSessionEntity session = sessions.findById(sessionId)
                    .orElseThrow(() -> new NoSuchElementException("Session no longer exists: " + sessionId));
            session.setMessageCount(session.getMessageCount() + 1);
            session.setLastMessagePreview(previewOf(message));
            session.setUpdatedAt(Instant.now());
            sessions.save(session);
        });
    }

    private void saveDraft(String profileId, PaymentDraft d, String sessionId, Instant now) {
        PaymentDraftEntity entity = drafts.findById(d.draftId()).orElseGet(() -> {
            PaymentDraftEntity created = new PaymentDraftEntity();
            created.setId(d.draftId());
            created.setCreatedAt(now);
            return created;
        });

        PayeeSummary p = d.selectedPayee();
        entity.setProfileId(profileId);
        entity.setSessionId(sessionId);
        entity.setPaymentType(d.paymentType());
        entity.setStatus(d.status());
        entity.setPayeeQueryText(d.payeeQueryText());
        entity.setSelectedPayeeId(p == null ? null : p.payeeId());
        entity.setSelectedPayeeName(p == null ? null : p.name());
        entity.setSelectedPayeeType(p == null ? null : p.payeeType());
        entity.setSelectedBankCode(p == null ? null : p.bankCode());
        entity.setSelectedBankName(p == null ? null : p.bankName());
        entity.setSelectedAccountNumber(p == null ? null : p.accountNumber());
        entity.setSelectedDisplayLabel(p == null ? null : p.displayLabel());
        entity.setAmount(d.amount() == null ? null : BigDecimal.valueOf(d.amount()).setScale(2, RoundingMode.HALF_UP));
        entity.setCurrency(d.currency());
        entity.setPaymentDate(d.paymentDate());
        entity.setDownstreamReference(d.downstreamReference());
        entity.setLastErrorCode(d.lastError() == null ? null : d.lastError().code());
        entity.setLastErrorMessage(d.lastError() == null ? null : d.lastError().message());
        entity.setContext(d.context() == null ? new HashMap<>() : new HashMap<>(d.context()));
        entity.setUpdatedAt(now);
        if (d.status() == PaymentDraftStatus.CONFIRMED && entity.getUserConfirmedAt() == null) {
            entity.setUserConfirmedAt(now);
        }
        if ((d.status() == PaymentDraftStatus.CONFIRMED
                || d.status() == PaymentDraftStatus.CANCELLED
                || d.status() == PaymentDraftStatus.FAILED)
                && entity.getCompletedAt() == null) {
            entity.setCompletedAt(now);
        }
        drafts.save(entity);
    }

    private PaymentDraft loadDraftBySession(String sessionId) {
        return drafts.findBySessionId(sessionId)
                .map(this::mapDraft)
                .orElse(null);
    }

    private ChatSessionDetail mapSession(ChatSessionEntity s, PaymentDraft draft) {
        return new ChatSessionDetail(
                s.getId(),
                s.getTitle(),
                s.getStatus(),
                s.getState(),
                s.getLlmProvider(),
                draft,
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }

    private PaymentDraft mapDraft(PaymentDraftEntity e) {
        PayeeSummary payee = null;
        if (e.getSelectedPayeeId() != null) {
            payee = new PayeeSummary(
                    e.getSelectedPayeeId(),
                    e.getSelectedPayeeName(),
                    e.getSelectedPayeeType(),
                    e.getSelectedBankCode(),
                    e.getSelectedBankName(),
                    e.getSelectedAccountNumber(),
                    e.getSelectedDisplayLabel()
            );
        }
        ErrorSummary lastError = (e.getLastErrorCode() == null && e.getLastErrorMessage() == null)
                ? null
                : new ErrorSummary(e.getLastErrorCode(), e.getLastErrorMessage());
        return new PaymentDraft(
                e.getId(),
                e.getSessionId(),
                e.getPaymentType(),
                e.getStatus(),
                e.getPayeeQueryText(),
                payee,
                e.getAmount() == null ? null : e.getAmount().doubleValue(),
                e.getCurrency(),
                e.getPaymentDate(),
                e.getDownstreamReference(),
                lastError,
                e.getContext() == null ? Map.of() : Map.copyOf(e.getContext()),
                e.getUpdatedAt()
        );
    }

    private ChatMessage mapMessage(ChatMessageEntity e) {
        return new ChatMessage(
                e.getId(),
                e.getSessionId(),
                e.getRole(),
                e.getKind(),
                e.getContentText(),
                e.getContentBlocks(),
                e.getMetadata(),
                e.getCreatedAt()
        );
    }

    private void ensureProfileExists(String profileId) {
        if (!profiles.existsById(profileId)) {
            throw new NoSuchElementException("Profile not found: " + profileId);
        }
    }

    private static String previewOf(ChatMessage message) {
        String text = null;
        if (message.text() != null && !message.text().isBlank()) text = message.text();
        else if (message.contentBlocks() != null && !message.contentBlocks().isEmpty()) {
            ContentBlock first = message.contentBlocks().get(0);
            if (first instanceof ContentBlock.TextBlock t) text = t.text();
            else if (first instanceof ContentBlock.InfoCardBlock i) text = i.text();
            else if (first instanceof ContentBlock.ErrorCardBlock e) text = e.text();
            else if (first instanceof ContentBlock.SummaryCardBlock s) text = s.title();
            else if (first instanceof ContentBlock.SelectableListBlock s) text = s.title();
        }
        if (text == null) return null;
        return text.length() > 160 ? text.substring(0, 160) : text;
    }
}
