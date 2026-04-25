package com.chat2pay.app.persistence.jdbc;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.PayeeSummary;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.domain.conversation.ChatSessionStatus;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.domain.conversation.LlmProviderType;
import com.chat2pay.app.domain.conversation.MessageKind;
import com.chat2pay.app.domain.conversation.MessageRole;
import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.domain.payment.PaymentType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Session + draft + message persistence. SessionRecord acts as a short-lived
 * snapshot whose mutations (setSession / messages().add) write through to
 * PostgreSQL.
 */
@Repository
public class JdbcSessionStore {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcSessionStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public class SessionRecord {
        private ChatSessionDetail snapshot;
        private final WriteThroughMessages messages;

        SessionRecord(ChatSessionDetail snapshot, List<ChatMessage> seed) {
            this.snapshot = snapshot;
            this.messages = new WriteThroughMessages(snapshot.sessionId(), seed);
        }

        public ChatSessionDetail session() { return snapshot; }

        public void setSession(ChatSessionDetail updated) {
            persistSessionAndDraft(updated);
            this.snapshot = updated;
        }

        public List<ChatMessage> messages() { return messages; }
    }

    private class WriteThroughMessages extends AbstractList<ChatMessage> {
        private final String sessionId;
        private final List<ChatMessage> cache;

        WriteThroughMessages(String sessionId, List<ChatMessage> seed) {
            this.sessionId = sessionId;
            this.cache = new ArrayList<>(seed);
        }

        @Override public int size() { return cache.size(); }
        @Override public ChatMessage get(int index) { return cache.get(index); }

        @Override
        public boolean add(ChatMessage message) {
            int seq = cache.size() + 1;
            insertMessage(sessionId, seq, message);
            return cache.add(message);
        }
    }

    // ---- public API -----------------------------------------------------------

    @Transactional
    public ChatSessionDetail create(String profileId, String title) {
        ensureProfileExists(profileId);
        String id = "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();
        String resolvedTitle = (title == null || title.isBlank()) ? "New conversation" : title;
        ChatSessionDetail detail = new ChatSessionDetail(
                id, resolvedTitle,
                ChatSessionStatus.ACTIVE, ConversationState.IDLE,
                LlmProviderType.COPILOT_PERSONAL, null, now, now);

        jdbc.sql("""
                insert into ctp_chat_session
                    (id, profile_id, title, status, state, llm_provider,
                     active_draft_id, last_message_preview, message_count,
                     created_at, updated_at)
                values (:id, :profileId, :title, :status, :state, :llm,
                        null, null, 0, :now, :now)
                """)
                .param("id", id)
                .param("profileId", profileId)
                .param("title", resolvedTitle)
                .param("status", ChatSessionStatus.ACTIVE.name())
                .param("state", ConversationState.IDLE.name())
                .param("llm", LlmProviderType.COPILOT_PERSONAL.name())
                .param("now", Timestamp.from(now))
                .update();
        return detail;
    }

    public SessionRecord get(String profileId, String sessionId) {
        ChatSessionDetail detail;
        try {
            detail = jdbc.sql("""
                    select s.id, s.title, s.status, s.state, s.llm_provider, s.active_draft_id,
                           s.created_at, s.updated_at
                      from ctp_chat_session s
                     where s.id = :id and s.profile_id = :profileId
                    """)
                    .param("id", sessionId)
                    .param("profileId", profileId)
                    .query((rs, rn) -> mapSession(rs, loadDraftBySession(sessionId)))
                    .single();
        } catch (EmptyResultDataAccessException e) {
            throw new NoSuchElementException("Session not found: " + sessionId);
        }
        List<ChatMessage> messages = loadMessages(sessionId);
        return new SessionRecord(detail, messages);
    }

    public List<ChatSessionDetail> listByProfile(String profileId) {
        return jdbc.sql("""
                select id, title, status, state, llm_provider, active_draft_id,
                       created_at, updated_at
                  from ctp_chat_session
                 where profile_id = :profileId
                 order by updated_at desc
                """)
                .param("profileId", profileId)
                .query((rs, rn) -> {
                    String draftId = rs.getString("active_draft_id");
                    PaymentDraft draft = draftId != null ? loadDraftById(draftId) : null;
                    return mapSession(rs, draft);
                })
                .list();
    }

    // ---- persistence ---------------------------------------------------------

    @Transactional
    void persistSessionAndDraft(ChatSessionDetail detail) {
        Instant now = Instant.now();
        PaymentDraft draft = detail.activeDraft();
        if (draft != null) {
            upsertDraft(draft, detail.sessionId(), now);
        }
        int updated = jdbc.sql("""
                update ctp_chat_session
                   set title = :title,
                       status = :status,
                       state = :state,
                       llm_provider = :llm,
                       active_draft_id = :draftId,
                       updated_at = :now
                 where id = :id
                """)
                .param("id", detail.sessionId())
                .param("title", detail.title())
                .param("status", detail.status().name())
                .param("state", detail.state().name())
                .param("llm", detail.llmProvider() == null ? null : detail.llmProvider().name())
                .param("draftId", draft == null ? null : draft.draftId())
                .param("now", Timestamp.from(now))
                .update();
        if (updated == 0) {
            throw new NoSuchElementException("Session no longer exists: " + detail.sessionId());
        }
    }

    @Transactional
    void insertMessage(String sessionId, int sequenceNo, ChatMessage message) {
        String contentBlocksJson = toJson(message.contentBlocks());
        String metadataJson = toJson(message.metadata());

        jdbc.sql("""
                insert into ctp_chat_message
                    (id, session_id, sequence_no, role, kind, content_text,
                     content_blocks_json, metadata_json, created_at)
                values (:id, :sid, :seq, :role, :kind, :text,
                        cast(:blocks as jsonb), cast(:meta as jsonb), :now)
                """)
                .param("id", message.messageId())
                .param("sid", sessionId)
                .param("seq", sequenceNo)
                .param("role", message.role().name())
                .param("kind", message.kind().name())
                .param("text", message.text())
                .param("blocks", contentBlocksJson)
                .param("meta", metadataJson)
                .param("now", Timestamp.from(message.createdAt() != null ? message.createdAt() : Instant.now()))
                .update();

        String preview = previewOf(message);
        jdbc.sql("""
                update ctp_chat_session
                   set message_count = message_count + 1,
                       last_message_preview = :preview,
                       updated_at = :now
                 where id = :id
                """)
                .param("id", sessionId)
                .param("preview", preview)
                .param("now", Timestamp.from(Instant.now()))
                .update();
    }

    private void upsertDraft(PaymentDraft d, String sessionId, Instant now) {
        PayeeSummary p = d.selectedPayee();
        jdbc.sql("""
                insert into ctp_payment_draft
                    (id, session_id, payment_type, status, payee_query_text,
                     selected_payee_id, selected_payee_name, selected_payee_type,
                     selected_bank_code, selected_bank_name, selected_account_number,
                     selected_display_label, amount, currency, payment_date,
                     downstream_reference, last_error_code, last_error_message,
                     context_json, created_at, updated_at)
                values (:id, :sid, :pt, :st, :pq,
                        :pid, :pname, :ptype,
                        :bcode, :bname, :acct,
                        :label, :amt, :ccy, :pdate,
                        :ref, :ecode, :emsg,
                        cast(:ctx as jsonb), :now, :now)
                on conflict (id) do update set
                    payment_type = excluded.payment_type,
                    status = excluded.status,
                    payee_query_text = excluded.payee_query_text,
                    selected_payee_id = excluded.selected_payee_id,
                    selected_payee_name = excluded.selected_payee_name,
                    selected_payee_type = excluded.selected_payee_type,
                    selected_bank_code = excluded.selected_bank_code,
                    selected_bank_name = excluded.selected_bank_name,
                    selected_account_number = excluded.selected_account_number,
                    selected_display_label = excluded.selected_display_label,
                    amount = excluded.amount,
                    currency = excluded.currency,
                    payment_date = excluded.payment_date,
                    downstream_reference = excluded.downstream_reference,
                    last_error_code = excluded.last_error_code,
                    last_error_message = excluded.last_error_message,
                    context_json = excluded.context_json,
                    updated_at = excluded.updated_at
                """)
                .param("id", d.draftId())
                .param("sid", sessionId)
                .param("pt", d.paymentType().name())
                .param("st", d.status().name())
                .param("pq", d.payeeQueryText())
                .param("pid", p == null ? null : p.payeeId())
                .param("pname", p == null ? null : p.name())
                .param("ptype", p == null ? null : p.payeeType())
                .param("bcode", p == null ? null : p.bankCode())
                .param("bname", p == null ? null : p.bankName())
                .param("acct", p == null ? null : p.accountNumber())
                .param("label", p == null ? null : p.displayLabel())
                .param("amt", d.amount())
                .param("ccy", d.currency())
                .param("pdate", d.paymentDate())
                .param("ref", d.downstreamReference())
                .param("ecode", d.lastError() == null ? null : d.lastError().code())
                .param("emsg", d.lastError() == null ? null : d.lastError().message())
                .param("ctx", toJson(d.context()))
                .param("now", Timestamp.from(now))
                .update();
    }

    // ---- mapping helpers -----------------------------------------------------

    private ChatSessionDetail mapSession(ResultSet rs, PaymentDraft draft) throws SQLException {
        String llm = rs.getString("llm_provider");
        return new ChatSessionDetail(
                rs.getString("id"),
                rs.getString("title"),
                ChatSessionStatus.valueOf(rs.getString("status")),
                ConversationState.valueOf(rs.getString("state")),
                llm == null ? null : LlmProviderType.valueOf(llm),
                draft,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private PaymentDraft loadDraftBySession(String sessionId) {
        return jdbc.sql("""
                select id, session_id, payment_type, status, payee_query_text,
                       selected_payee_id, selected_payee_name, selected_payee_type,
                       selected_bank_code, selected_bank_name, selected_account_number,
                       selected_display_label, amount, currency, payment_date,
                       downstream_reference, last_error_code, last_error_message,
                       context_json::text as context_json_text, updated_at
                  from ctp_payment_draft
                 where session_id = :sid
                """)
                .param("sid", sessionId)
                .query((rs, rn) -> mapDraft(rs))
                .optional()
                .orElse(null);
    }

    private PaymentDraft loadDraftById(String draftId) {
        return jdbc.sql("""
                select id, session_id, payment_type, status, payee_query_text,
                       selected_payee_id, selected_payee_name, selected_payee_type,
                       selected_bank_code, selected_bank_name, selected_account_number,
                       selected_display_label, amount, currency, payment_date,
                       downstream_reference, last_error_code, last_error_message,
                       context_json::text as context_json_text, updated_at
                  from ctp_payment_draft
                 where id = :id
                """)
                .param("id", draftId)
                .query((rs, rn) -> mapDraft(rs))
                .optional()
                .orElse(null);
    }

    private PaymentDraft mapDraft(ResultSet rs) throws SQLException {
        PayeeSummary payee = null;
        String pid = rs.getString("selected_payee_id");
        if (pid != null) {
            payee = new PayeeSummary(
                    pid,
                    rs.getString("selected_payee_name"),
                    rs.getString("selected_payee_type"),
                    rs.getString("selected_bank_code"),
                    rs.getString("selected_bank_name"),
                    rs.getString("selected_account_number"),
                    rs.getString("selected_display_label")
            );
        }
        Object amtObj = rs.getObject("amount");
        Double amount = amtObj == null ? null : ((java.math.BigDecimal) amtObj).doubleValue();
        java.sql.Date pd = rs.getDate("payment_date");
        LocalDate paymentDate = pd == null ? null : pd.toLocalDate();

        String ecode = rs.getString("last_error_code");
        String emsg = rs.getString("last_error_message");
        com.chat2pay.app.api.dto.ChatDtos.ErrorSummary lastError =
                (ecode == null && emsg == null) ? null
                        : new com.chat2pay.app.api.dto.ChatDtos.ErrorSummary(ecode, emsg);

        Map<String, Object> context = fromJson(rs.getString("context_json_text"),
                new TypeReference<Map<String, Object>>() {});

        return new PaymentDraft(
                rs.getString("id"),
                rs.getString("session_id"),
                PaymentType.valueOf(rs.getString("payment_type")),
                PaymentDraftStatus.valueOf(rs.getString("status")),
                rs.getString("payee_query_text"),
                payee,
                amount,
                rs.getString("currency"),
                paymentDate,
                rs.getString("downstream_reference"),
                lastError,
                context,
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private List<ChatMessage> loadMessages(String sessionId) {
        return jdbc.sql("""
                select id, session_id, role, kind, content_text,
                       content_blocks_json::text as blocks_text,
                       metadata_json::text as metadata_text,
                       created_at
                  from ctp_chat_message
                 where session_id = :sid
                 order by sequence_no
                """)
                .param("sid", sessionId)
                .query((rs, rn) -> mapMessage(rs))
                .list();
    }

    private ChatMessage mapMessage(ResultSet rs) throws SQLException {
        List<ContentBlock> blocks = fromJson(rs.getString("blocks_text"),
                new TypeReference<List<ContentBlock>>() {});
        Map<String, Object> metadata = fromJson(rs.getString("metadata_text"),
                new TypeReference<Map<String, Object>>() {});
        return new ChatMessage(
                rs.getString("id"),
                rs.getString("session_id"),
                MessageRole.valueOf(rs.getString("role")),
                MessageKind.valueOf(rs.getString("kind")),
                rs.getString("content_text"),
                blocks,
                metadata,
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private void ensureProfileExists(String profileId) {
        Integer count = jdbc.sql("select count(*) from ctp_profile where id = :id")
                .param("id", profileId)
                .query(Integer.class)
                .single();
        if (count == null || count == 0) {
            throw new NoSuchElementException("Profile not found: " + profileId);
        }
    }

    private String toJson(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?,?> m && m.isEmpty()) return null;
        if (value instanceof List<?> l && l.isEmpty()) return null;
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize JSON", e); }
    }

    private <T> T fromJson(String raw, TypeReference<T> typeRef) {
        if (raw == null || raw.isBlank()) return null;
        try { return mapper.readValue(raw, typeRef); }
        catch (Exception e) { throw new RuntimeException("Failed to read JSON", e); }
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
