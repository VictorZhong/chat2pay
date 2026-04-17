package com.chat2pay.app.persistence.chat;

import com.chat2pay.app.api.ApiModels;
import com.chat2pay.app.application.chat.ConversationSessionRepository;
import com.chat2pay.app.domain.ChatSessionStatus;
import com.chat2pay.app.domain.ConversationMessage;
import com.chat2pay.app.domain.ConversationSession;
import com.chat2pay.app.domain.JourneyType;
import com.chat2pay.app.domain.MessageRole;
import com.chat2pay.app.domain.MessageType;
import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.TransferStatus;
import com.chat2pay.app.domain.WorkflowState;
import com.chat2pay.app.integration.downstream.RegisteredPayee;
import com.chat2pay.app.persistence.support.JsonCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcConversationSessionRepository implements ConversationSessionRepository {

    private static final TypeReference<List<LinkedHashMap<String, Object>>> JSON_OBJECT_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<RegisteredPayee>> REGISTERED_PAYEE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, Object>> STRING_OBJECT_MAP = new TypeReference<>() {
    };

    private static final String SELECT_SESSIONS_BY_PROFILE_ID = """
            select
                id,
                profile_id,
                title,
                status,
                workflow_state,
                journey_type,
                created_at,
                updated_at
            from ctp_chat_session
            where profile_id = :profileId
            order by updated_at desc, created_at desc
            """;

    private static final String SELECT_SESSION_BY_PROFILE_ID_AND_ID = """
            select
                id,
                profile_id,
                title,
                status,
                workflow_state,
                journey_type,
                created_at,
                updated_at
            from ctp_chat_session
            where profile_id = :profileId
              and id = :sessionId
            """;

    private static final String UPDATE_SESSION = """
            update ctp_chat_session
            set profile_id = :profileId,
                title = :title,
                status = :status,
                workflow_state = :workflowState,
                journey_type = :journeyType,
                message_count = :messageCount,
                updated_at = :updatedAt
            where id = :id
            """;

    private static final String INSERT_SESSION = """
            insert into ctp_chat_session (
                id,
                profile_id,
                title,
                status,
                workflow_state,
                journey_type,
                message_count,
                created_at,
                updated_at
            ) values (
                :id,
                :profileId,
                :title,
                :status,
                :workflowState,
                :journeyType,
                :messageCount,
                :createdAt,
                :updatedAt
            )
            """;

    private static final String DELETE_MESSAGES_BY_SESSION_ID = """
            delete from ctp_chat_message
            where session_id = :sessionId
            """;

    private static final String INSERT_MESSAGE = """
            insert into ctp_chat_message (
                id,
                session_id,
                sequence_no,
                role,
                message_type,
                content_text,
                content_blocks_json,
                created_at
            ) values (
                :id,
                :sessionId,
                :sequenceNo,
                :role,
                :messageType,
                :contentText,
                :contentBlocksJson,
                :createdAt
            )
            """;

    private static final String SELECT_MESSAGES_BY_SESSION_ID = """
            select
                id,
                session_id,
                sequence_no,
                role,
                message_type,
                content_text,
                content_blocks_json,
                created_at
            from ctp_chat_message
            where session_id = :sessionId
            order by sequence_no asc
            """;

    private static final String DELETE_DRAFT_BY_SESSION_ID = """
            delete from ctp_payment_draft
            where session_id = :sessionId
            """;

    private static final String UPDATE_DRAFT = """
            update ctp_payment_draft
            set session_id = :sessionId,
                journey_type = :journeyType,
                status = :status,
                workflow_state = :workflowState,
                source_account_id = :sourceAccountId,
                source_account_display = :sourceAccountDisplay,
                payee_name_input = :payeeNameInput,
                payee_id_index = :payeeIdIndex,
                payee_type = :payeeType,
                payee_display = :payeeDisplay,
                amount = :amount,
                currency = :currency,
                note = :note,
                review_summary_json = :reviewSummaryJson,
                downstream_references_json = :downstreamReferencesJson,
                transfer_reference = :transferReference,
                additional_context_json = :additionalContextJson,
                candidate_payees_json = :candidatePayeesJson,
                updated_at = :updatedAt
            where id = :id
            """;

    private static final String INSERT_DRAFT = """
            insert into ctp_payment_draft (
                id,
                session_id,
                journey_type,
                status,
                workflow_state,
                source_account_id,
                source_account_display,
                payee_name_input,
                payee_id_index,
                payee_type,
                payee_display,
                amount,
                currency,
                note,
                review_summary_json,
                downstream_references_json,
                transfer_reference,
                additional_context_json,
                candidate_payees_json,
                created_at,
                updated_at
            ) values (
                :id,
                :sessionId,
                :journeyType,
                :status,
                :workflowState,
                :sourceAccountId,
                :sourceAccountDisplay,
                :payeeNameInput,
                :payeeIdIndex,
                :payeeType,
                :payeeDisplay,
                :amount,
                :currency,
                :note,
                :reviewSummaryJson,
                :downstreamReferencesJson,
                :transferReference,
                :additionalContextJson,
                :candidatePayeesJson,
                :createdAt,
                :updatedAt
            )
            """;

    private static final String SELECT_DRAFT_BY_SESSION_ID = """
            select
                id,
                session_id,
                journey_type,
                status,
                workflow_state,
                source_account_id,
                source_account_display,
                payee_name_input,
                payee_id_index,
                payee_type,
                payee_display,
                amount,
                currency,
                note,
                review_summary_json,
                downstream_references_json,
                transfer_reference,
                additional_context_json,
                candidate_payees_json,
                created_at,
                updated_at
            from ctp_payment_draft
            where session_id = :sessionId
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public JdbcConversationSessionRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public List<ConversationSession> findAllByProfileId(String profileId) {
        List<SessionRow> sessionRows = jdbcTemplate.query(
                SELECT_SESSIONS_BY_PROFILE_ID,
                new MapSqlParameterSource("profileId", profileId),
                (resultSet, rowNum) -> mapSessionRow(resultSet));
        return sessionRows.stream()
                .map(this::hydrateSession)
                .toList();
    }

    @Override
    public Optional<ConversationSession> findByProfileIdAndId(String profileId, String sessionId) {
        List<SessionRow> sessionRows = jdbcTemplate.query(
                SELECT_SESSION_BY_PROFILE_ID_AND_ID,
                new MapSqlParameterSource(Map.of(
                        "profileId", profileId,
                        "sessionId", sessionId)),
                (resultSet, rowNum) -> mapSessionRow(resultSet));
        return sessionRows.stream().findFirst().map(this::hydrateSession);
    }

    @Override
    @Transactional
    public ConversationSession save(ConversationSession session) {
        MapSqlParameterSource sessionParams = new MapSqlParameterSource()
                .addValue("id", session.getId())
                .addValue("profileId", session.getProfileId())
                .addValue("title", session.getTitle())
                .addValue("status", session.getStatus().name())
                .addValue("workflowState", session.getWorkflowState().name())
                .addValue("journeyType", enumName(session.getJourneyType()))
                .addValue("messageCount", session.getMessages().size())
                .addValue("createdAt", timestamp(session.getCreatedAt()))
                .addValue("updatedAt", timestamp(session.getUpdatedAt()));

        int updatedRows = jdbcTemplate.update(UPDATE_SESSION, sessionParams);
        if (updatedRows == 0) {
            jdbcTemplate.update(INSERT_SESSION, sessionParams);
        }

        replaceMessages(session);
        replaceDraft(session.getActiveDraft(), session.getId());
        return session;
    }

    private ConversationSession hydrateSession(SessionRow sessionRow) {
        ConversationSession session = new ConversationSession(
                sessionRow.id(),
                sessionRow.profileId(),
                sessionRow.title(),
                sessionRow.createdAt());
        session.setStatus(sessionRow.status());
        session.setWorkflowState(sessionRow.workflowState());
        session.setJourneyType(sessionRow.journeyType());

        PaymentDraft draft = loadDraft(sessionRow.id()).orElse(null);
        Instant draftUpdatedAt = draft != null ? draft.getLastUpdatedAt() : null;
        if (draft != null) {
            session.setActiveDraft(draft);
        }

        loadMessages(sessionRow.id()).forEach(session::addMessage);
        session.touch(sessionRow.updatedAt());
        if (draft != null && draftUpdatedAt != null) {
            draft.touch(draftUpdatedAt);
        }
        return session;
    }

    private void replaceMessages(ConversationSession session) {
        jdbcTemplate.update(DELETE_MESSAGES_BY_SESSION_ID, new MapSqlParameterSource("sessionId", session.getId()));
        if (session.getMessages().isEmpty()) {
            return;
        }

        SqlParameterSource[] batch = session.getMessages().stream()
                .map(this::toMessageParameters)
                .toArray(SqlParameterSource[]::new);
        jdbcTemplate.batchUpdate(INSERT_MESSAGE, batch);
    }

    private void replaceDraft(PaymentDraft draft, String sessionId) {
        if (draft == null) {
            jdbcTemplate.update(DELETE_DRAFT_BY_SESSION_ID, new MapSqlParameterSource("sessionId", sessionId));
            return;
        }

        MapSqlParameterSource params = toDraftParameters(draft);
        int updatedRows = jdbcTemplate.update(UPDATE_DRAFT, params);
        if (updatedRows == 0) {
            jdbcTemplate.update(INSERT_DRAFT, params);
        }
    }

    private List<ConversationMessage> loadMessages(String sessionId) {
        return jdbcTemplate.query(
                SELECT_MESSAGES_BY_SESSION_ID,
                new MapSqlParameterSource("sessionId", sessionId),
                this::mapMessage);
    }

    private Optional<PaymentDraft> loadDraft(String sessionId) {
        List<PaymentDraft> drafts = jdbcTemplate.query(
                SELECT_DRAFT_BY_SESSION_ID,
                new MapSqlParameterSource("sessionId", sessionId),
                this::mapDraft);
        return drafts.stream().findFirst();
    }

    private SessionRow mapSessionRow(ResultSet resultSet) throws SQLException {
        return new SessionRow(
                resultSet.getString("id"),
                resultSet.getString("profile_id"),
                resultSet.getString("title"),
                ChatSessionStatus.valueOf(resultSet.getString("status")),
                WorkflowState.valueOf(resultSet.getString("workflow_state")),
                nullableEnum(resultSet.getString("journey_type"), JourneyType::valueOf),
                toInstant(resultSet, "created_at"),
                toInstant(resultSet, "updated_at"));
    }

    private ConversationMessage mapMessage(ResultSet resultSet, int rowNum) throws SQLException {
        return new ConversationMessage(
                resultSet.getString("id"),
                resultSet.getString("session_id"),
                resultSet.getInt("sequence_no"),
                MessageRole.valueOf(resultSet.getString("role")),
                MessageType.valueOf(resultSet.getString("message_type")),
                resultSet.getString("content_text"),
                List.copyOf(readContentBlocks(resultSet.getString("content_blocks_json"))),
                toInstant(resultSet, "created_at"));
    }

    private PaymentDraft mapDraft(ResultSet resultSet, int rowNum) throws SQLException {
        PaymentDraft draft = new PaymentDraft(
                resultSet.getString("id"),
                resultSet.getString("session_id"),
                JourneyType.valueOf(resultSet.getString("journey_type")),
                resultSet.getString("source_account_id"),
                resultSet.getString("source_account_display"),
                toInstant(resultSet, "created_at"));
        draft.setStatus(TransferStatus.valueOf(resultSet.getString("status")));
        draft.setWorkflowState(WorkflowState.valueOf(resultSet.getString("workflow_state")));
        draft.setPayeeNameInput(resultSet.getString("payee_name_input"));
        draft.setPayeeIdIndex(resultSet.getString("payee_id_index"));
        draft.setPayeeType(resultSet.getString("payee_type"));
        draft.setPayeeDisplay(resultSet.getString("payee_display"));
        draft.setAmount(resultSet.getBigDecimal("amount"));
        draft.setCurrency(resultSet.getString("currency"));
        draft.setNote(resultSet.getString("note"));
        draft.setReviewSummary(new LinkedHashMap<>(jsonCodec.read(
                resultSet.getString("review_summary_json"),
                STRING_OBJECT_MAP,
                LinkedHashMap::new)));
        draft.setDownstreamReferences(new LinkedHashMap<>(jsonCodec.read(
                resultSet.getString("downstream_references_json"),
                STRING_OBJECT_MAP,
                LinkedHashMap::new)));
        draft.setTransferReference(resultSet.getString("transfer_reference"));
        draft.setAdditionalContext(new LinkedHashMap<>(jsonCodec.read(
                resultSet.getString("additional_context_json"),
                STRING_OBJECT_MAP,
                LinkedHashMap::new)));
        draft.replaceCandidatePayees(jsonCodec.read(
                resultSet.getString("candidate_payees_json"),
                REGISTERED_PAYEE_LIST,
                List::of));
        draft.touch(toInstant(resultSet, "updated_at"));
        return draft;
    }

    private MapSqlParameterSource toMessageParameters(ConversationMessage message) {
        return new MapSqlParameterSource()
                .addValue("id", message.id())
                .addValue("sessionId", message.sessionId())
                .addValue("sequenceNo", message.sequenceNo())
                .addValue("role", message.role().name())
                .addValue("messageType", message.messageType().name())
                .addValue("contentText", message.text())
                .addValue("contentBlocksJson", writeContentBlocks(message.contentBlocks()))
                .addValue("createdAt", timestamp(message.createdAt()));
    }

    private MapSqlParameterSource toDraftParameters(PaymentDraft draft) {
        return new MapSqlParameterSource()
                .addValue("id", draft.getId())
                .addValue("sessionId", draft.getSessionId())
                .addValue("journeyType", draft.getJourneyType().name())
                .addValue("status", draft.getStatus().name())
                .addValue("workflowState", draft.getWorkflowState().name())
                .addValue("sourceAccountId", draft.getSourceAccountId())
                .addValue("sourceAccountDisplay", draft.getSourceAccountDisplay())
                .addValue("payeeNameInput", draft.getPayeeNameInput())
                .addValue("payeeIdIndex", draft.getPayeeIdIndex())
                .addValue("payeeType", draft.getPayeeType())
                .addValue("payeeDisplay", draft.getPayeeDisplay())
                .addValue("amount", draft.getAmount())
                .addValue("currency", draft.getCurrency())
                .addValue("note", draft.getNote())
                .addValue("reviewSummaryJson", jsonCodec.writeNullable(draft.getReviewSummary()))
                .addValue("downstreamReferencesJson", jsonCodec.writeNullable(draft.getDownstreamReferences()))
                .addValue("transferReference", draft.getTransferReference())
                .addValue("additionalContextJson", jsonCodec.writeNullable(draft.getAdditionalContext()))
                .addValue("candidatePayeesJson", jsonCodec.writeNullable(draft.getCandidatePayees().values()))
                .addValue("createdAt", timestamp(draft.getLastUpdatedAt()))
                .addValue("updatedAt", timestamp(draft.getLastUpdatedAt()));
    }

    private String writeContentBlocks(List<ApiModels.ContentBlock> contentBlocks) {
        if (contentBlocks == null || contentBlocks.isEmpty()) {
            return null;
        }
        List<LinkedHashMap<String, Object>> rawBlocks = contentBlocks.stream()
                .map(this::toPersistedContentBlock)
                .toList();
        return jsonCodec.writeNullable(rawBlocks);
    }

    private List<ApiModels.ContentBlock> readContentBlocks(String json) {
        List<LinkedHashMap<String, Object>> rawBlocks = jsonCodec.read(json, JSON_OBJECT_LIST, List::of);
        return rawBlocks.stream()
                .map(this::toContentBlock)
                .toList();
    }

    private ApiModels.ContentBlock toContentBlock(Map<String, Object> raw) {
        String type = raw.get("type") instanceof String value ? value : null;
        if (type == null || type.isBlank()) {
            throw new IllegalStateException("Persisted content block is missing type: " + raw);
        }
        return switch (type) {
            case "TEXT" -> jsonCodec.convert(raw, ApiModels.TextBlock.class);
            case "SUMMARY_CARD" -> jsonCodec.convert(raw, ApiModels.SummaryCardBlock.class);
            case "SELECTABLE_LIST" -> jsonCodec.convert(raw, ApiModels.SelectableListBlock.class);
            case "SIMPLE_FORM" -> jsonCodec.convert(raw, ApiModels.SimpleFormBlock.class);
            case "ERROR_CARD" -> jsonCodec.convert(raw, ApiModels.ErrorCardBlock.class);
            case "INFO_CARD" -> jsonCodec.convert(raw, ApiModels.InfoCardBlock.class);
            default -> throw new IllegalStateException("Unsupported persisted content block type: " + type);
        };
    }

    private LinkedHashMap<String, Object> toPersistedContentBlock(ApiModels.ContentBlock block) {
        return switch (block) {
            case ApiModels.TextBlock textBlock -> {
                LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
                raw.put("blockId", textBlock.blockId());
                raw.put("type", "TEXT");
                raw.put("title", textBlock.title());
                raw.put("text", textBlock.text());
                raw.put("metadata", textBlock.metadata());
                yield raw;
            }
            case ApiModels.SummaryCardBlock summaryCardBlock -> {
                LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
                raw.put("blockId", summaryCardBlock.blockId());
                raw.put("type", "SUMMARY_CARD");
                raw.put("title", summaryCardBlock.title());
                raw.put("fields", jsonCodec.convert(summaryCardBlock.fields(), JSON_OBJECT_LIST));
                raw.put("metadata", summaryCardBlock.metadata());
                yield raw;
            }
            case ApiModels.SelectableListBlock selectableListBlock -> {
                LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
                raw.put("blockId", selectableListBlock.blockId());
                raw.put("type", "SELECTABLE_LIST");
                raw.put("title", selectableListBlock.title());
                raw.put("selectionMode", selectableListBlock.selectionMode());
                raw.put("items", jsonCodec.convert(selectableListBlock.items(), JSON_OBJECT_LIST));
                raw.put("metadata", selectableListBlock.metadata());
                yield raw;
            }
            case ApiModels.SimpleFormBlock simpleFormBlock -> {
                LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
                raw.put("blockId", simpleFormBlock.blockId());
                raw.put("type", "SIMPLE_FORM");
                raw.put("title", simpleFormBlock.title());
                raw.put("fields", jsonCodec.convert(simpleFormBlock.fields(), JSON_OBJECT_LIST));
                raw.put("submitLabel", simpleFormBlock.submitLabel());
                raw.put("metadata", simpleFormBlock.metadata());
                yield raw;
            }
            case ApiModels.ErrorCardBlock errorCardBlock -> {
                LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
                raw.put("blockId", errorCardBlock.blockId());
                raw.put("type", "ERROR_CARD");
                raw.put("title", errorCardBlock.title());
                raw.put("text", errorCardBlock.text());
                raw.put("metadata", errorCardBlock.metadata());
                yield raw;
            }
            case ApiModels.InfoCardBlock infoCardBlock -> {
                LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
                raw.put("blockId", infoCardBlock.blockId());
                raw.put("type", "INFO_CARD");
                raw.put("title", infoCardBlock.title());
                raw.put("text", infoCardBlock.text());
                raw.put("metadata", infoCardBlock.metadata());
                yield raw;
            }
        };
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant toInstant(ResultSet resultSet, String columnName) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <T> T nullableEnum(String value, java.util.function.Function<String, T> mapper) {
        return value == null || value.isBlank() ? null : mapper.apply(value);
    }

    private record SessionRow(
            String id,
            String profileId,
            String title,
            ChatSessionStatus status,
            WorkflowState workflowState,
            JourneyType journeyType,
            Instant createdAt,
            Instant updatedAt) {
    }
}
