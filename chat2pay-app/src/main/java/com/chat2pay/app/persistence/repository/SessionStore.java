package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.DebitAccountSummary;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Session + draft + message persistence. SessionRecord acts as a short-lived
 * turn snapshot; applyTurn persists all mutations in one transaction.
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
        private final CopyOnWriteArrayList<ChatMessage> messages;
        private final int persistedMessageCount;

        SessionRecord(String profileId, ChatSessionDetail snapshot, List<ChatMessage> seed) {
            this.profileId = profileId;
            this.snapshot = snapshot;
            this.messages = new CopyOnWriteArrayList<>(seed);
            this.persistedMessageCount = seed.size();
        }

        public String profileId() { return profileId; }

        public ChatSessionDetail session() { return snapshot; }

        public void setSession(ChatSessionDetail updated) {
            this.snapshot = updated;
        }

        /** Refresh the in-memory snapshot from the DB without writing through. */
        public void reloadSnapshot() {
            ChatSessionEntity entity = sessions.findByIdAndProfileId(snapshot.sessionId(), profileId)
                    .orElse(null);
            if (entity == null) return;
            this.snapshot = mapSession(entity, loadDraftBySession(entity.getId()));
        }

        public List<ChatMessage> messages() {
            return messages;
        }

        public List<ChatMessage> messageSnapshot() {
            return List.copyOf(messages);
        }

        private List<ChatMessage> newMessages() {
            if (messages.size() <= persistedMessageCount) return List.of();
            return new ArrayList<>(messages.subList(persistedMessageCount, messages.size()));
        }
    }

    public ChatSessionDetail create(String profileId, String title) {
        return transactions.execute(status -> {
            ensureProfileExists(profileId);
            String id = "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Instant now = Instant.now();
            String resolvedTitle = normalizeTitle(title);

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
        return loadRecord(profileId, sessionId);
    }

    public <T> T applyTurn(String profileId, String sessionId, Function<SessionRecord, T> mutator) {
        return transactions.execute(status -> {
            SessionRecord record = loadRecord(profileId, sessionId);
            T result = mutator.apply(record);
            persistRecord(record);
            return result;
        });
    }

    public List<ChatMessage> listMessages(String profileId, String sessionId) {
        return transactions.execute(status -> loadRecord(profileId, sessionId).messageSnapshot());
    }

    private SessionRecord loadRecord(String profileId, String sessionId) {
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

    public void delete(String profileId, String sessionId) {
        transactions.executeWithoutResult(status -> {
            ChatSessionEntity entity = sessions.findByIdAndProfileId(sessionId, profileId)
                    .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
            // Break the deferred FK from session.active_draft_id before removing the draft.
            entity.setActiveDraftId(null);
            sessions.save(entity);
            drafts.findBySessionId(sessionId).ifPresent(drafts::delete);
            messages.deleteBySessionId(sessionId);
            sessions.delete(entity);
        });
    }

    public ChatSessionDetail rename(String profileId, String sessionId, String title) {
        return transactions.execute(status -> {
            ChatSessionEntity entity = sessions.findByIdAndProfileId(sessionId, profileId)
                    .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
            String trimmed = title == null ? "" : title.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Session title must not be blank.");
            }
            if (trimmed.length() > 120) trimmed = trimmed.substring(0, 120);
            entity.setTitle(trimmed);
            entity.setTitleLocked(true);
            entity.setUpdatedAt(Instant.now());
            sessions.save(entity);
            return mapSession(entity, loadDraftBySession(sessionId));
        });
    }

    /**
     * Replaces the title only when the user has not manually renamed the session.
     * Used by the AI-suggested title flow.
     */
    public void applyAiSuggestedTitle(String profileId, String sessionId, String suggestedTitle) {
        if (suggestedTitle == null || suggestedTitle.isBlank()) return;
        transactions.executeWithoutResult(status -> {
            ChatSessionEntity entity = sessions.findByIdAndProfileId(sessionId, profileId)
                    .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
            if (entity.isTitleLocked()) return;
            String trimmed = suggestedTitle.trim();
            if (trimmed.length() > 120) trimmed = trimmed.substring(0, 120);
            entity.setTitle(trimmed);
            entity.setUpdatedAt(Instant.now());
            sessions.save(entity);
        });
    }

    private void persistRecord(SessionRecord record) {
        ChatSessionDetail detail = record.session();
        Instant now = Instant.now();
        PaymentDraft draft = detail.activeDraft();
        if (draft != null) saveDraft(record.profileId(), draft, detail.sessionId(), now);

        ChatSessionEntity entity = sessions.findByIdAndProfileId(detail.sessionId(), record.profileId())
                .orElseThrow(() -> new NoSuchElementException("Session no longer exists: " + detail.sessionId()));

        List<ChatMessage> newMessages = record.newMessages();
        int sequenceNo = record.persistedMessageCount;
        for (ChatMessage message : newMessages) {
            saveMessage(record.profileId(), detail.sessionId(), ++sequenceNo, message);
        }

        // Snapshot may carry an auto-suggested title; user-locked titles win.
        if (!entity.isTitleLocked()) {
            entity.setTitle(detail.title());
        }
        entity.setStatus(detail.status());
        entity.setState(detail.state());
        entity.setLlmProvider(detail.llmProvider());
        entity.setActiveDraftId(draft == null ? null : draft.draftId());
        if (!newMessages.isEmpty()) {
            entity.setMessageCount(record.persistedMessageCount + newMessages.size());
            entity.setLastMessagePreview(previewOf(newMessages.get(newMessages.size() - 1)));
        }
        entity.setUpdatedAt(now);
        sessions.save(entity);
    }

    private void saveMessage(String profileId, String sessionId, int sequenceNo, ChatMessage message) {
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
        DebitAccountSummary debit = d.selectedDebitAccount();
        entity.setSelectedDebitAccountId(debit == null ? null : debit.accountId());
        entity.setSelectedDebitAccountDisplay(debit == null ? null : debit.accountDisplay());
        entity.setSelectedDebitProductCategoryCode(debit == null ? null : debit.productCategoryCode());
        entity.setSelectedDebitProductDescription(debit == null ? null : debit.productDescription());
        entity.setSelectedDebitDisplayLabel(debit == null ? null : debit.displayLabel());
        entity.setSelectedDebitCurrency(debit == null ? null : debit.currency());
        entity.setAmount(d.amount() == null ? null : d.amount().setScale(2, RoundingMode.HALF_UP));
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
                s.isTitleLocked(),
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
        DebitAccountSummary debitAccount = null;
        if (e.getSelectedDebitAccountId() != null || e.getSelectedDebitAccountDisplay() != null) {
            debitAccount = new DebitAccountSummary(
                    e.getSelectedDebitAccountId(),
                    e.getSelectedDebitAccountDisplay(),
                    e.getSelectedDebitProductCategoryCode(),
                    e.getSelectedDebitProductDescription(),
                    e.getSelectedDebitDisplayLabel(),
                    e.getSelectedDebitCurrency()
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
                debitAccount,
                e.getAmount(),
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

    private static String normalizeTitle(String title) {
        String resolved = (title == null || title.isBlank()) ? "New conversation" : title.trim();
        return resolved.length() > 120 ? resolved.substring(0, 120) : resolved;
    }
}
