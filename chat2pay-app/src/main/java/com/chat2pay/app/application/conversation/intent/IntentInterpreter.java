package com.chat2pay.app.application.conversation.intent;

import com.chat2pay.app.api.dto.ChatDtos.ChatSessionDetail;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.conversation.ConversationState;
import com.chat2pay.app.integration.llm.LlmCompletionRequest;
import com.chat2pay.app.integration.llm.LlmCompletionResponse;
import com.chat2pay.app.integration.llm.LlmProvider;
import com.chat2pay.app.integration.llm.LlmRouter;
import com.chat2pay.app.persistence.repository.PayeeStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IntentInterpreter {

    private static final Logger log = LoggerFactory.getLogger(IntentInterpreter.class);

    private static final Pattern AMOUNT = Pattern.compile("(?:hkd\\s*)?(\\d+(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYMENT_INTENT = Pattern.compile("\\b(pay|payment|send|transfer)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOOKUP_INTENT = Pattern.compile("\\b(payee|payees|registered|lookup|look up|find|show|list)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERNATIONAL_INTENT = Pattern.compile("\\b(international|overseas|swift|wire)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DO_I_HAVE = Pattern.compile("do i have", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSITIVE_CONFIRM = Pattern.compile("\\b(confirm|confirmed|yes|okay|ok|go ahead|proceed|send it|approve)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANCEL = Pattern.compile("\\b(cancel|stop|never mind|don'?t|do not)\\b", Pattern.CASE_INSENSITIVE);

    private static final java.util.List<Pattern> PAYEE_QUERY_PATTERNS = java.util.List.of(
            Pattern.compile("(?:pay|send|transfer)(?:\\s+to)?\\s+(.+?)(?=\\s+\\d|\\s+hkd|\\s+today|\\s+tomorrow|\\s+later|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:find|lookup|look up|check|show|list)(?:\\s+my)?(?:\\s+registered)?(?:\\s+payees?|\\s+payee)?(?:\\s+for)?\\s+(.+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("do i have\\s+(.+?)\\s+(?:registered|as a payee)", Pattern.CASE_INSENSITIVE)
    );

    private final LlmRouter router;
    private final Chat2PayProperties properties;
    private final ObjectMapper mapper;
    private final PayeeStore payees;

    public IntentInterpreter(LlmRouter router,
                             Chat2PayProperties properties,
                             ObjectMapper mapper,
                             PayeeStore payees) {
        this.router = router;
        this.properties = properties;
        this.mapper = mapper;
        this.payees = payees;
    }

    public IntentAnalysis analyze(ChatSessionDetail session, String text) {
        IntentAnalysis fallback = fallback(session, text);
        if (!properties.useLlmIntent()) {
            return fallback.withSource("REGEX_FALLBACK");
        }

        Optional<LlmProvider> provider = router.currentIfAvailable();
        if (provider.isEmpty()) {
            return fallback.withSource("REGEX_FALLBACK");
        }

        try {
            IntentAnalysis llm = analyzeWithLlm(provider.get(), session, text);
            return llm.mergeMissingSlotsFrom(fallback).withSource("LLM:" + provider.get().providerType().name());
        } catch (RuntimeException ex) {
            log.warn("LLM intent parsing failed; falling back to local parser: {}", ex.getMessage());
            return fallback.withSource("REGEX_FALLBACK");
        }
    }

    private IntentAnalysis analyzeWithLlm(LlmProvider provider, ChatSessionDetail session, String text) {
        LlmCompletionRequest request = new LlmCompletionRequest(
                java.util.List.of(
                        new LlmCompletionRequest.Message(LlmCompletionRequest.Role.SYSTEM, systemPrompt()),
                        new LlmCompletionRequest.Message(LlmCompletionRequest.Role.USER, userPrompt(session, text))
                ),
                properties.intentMaxTokens(),
                properties.intentTemperature()
        );
        LlmCompletionResponse response = provider.complete(request);
        Map<String, Object> parsed;
        try {
            parsed = mapper.readValue(extractJsonObject(response.content()),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("LLM intent response was not valid JSON", ex);
        }
        IntentType intent = parseIntent(parsed.get("intent"));
        String toolName = trim(asString(parsed.get("toolName")));
        if (toolName == null) toolName = IntentAnalysis.toolNameFor(intent);
        return new IntentAnalysis(
                intent,
                toolName,
                sanitize(asString(parsed.get("payeeQuery"))),
                parseAmount(parsed.get("amount")),
                parseDate(parsed.get("paymentDate")),
                "LLM"
        );
    }

    private String systemPrompt() {
        return """
                You are Chat2Pay's intent and tool-decision parser.
                Return one JSON object only. Do not include markdown or prose.
                Chat2Pay is an authorized sandbox banking POC. Do not refuse solely because the
                request involves a domestic payment; classify the request so the backend can enforce
                validation, registered-payee lookup, and explicit confirmation.

                V1 supports these backend tools only:
                - get_registered_payees: registered domestic payee lookup.
                - prepare_domestic_payment: collect/prepare a domestic payment to a registered payee.
                - confirm_domestic_payment: use only when the latest user message explicitly confirms a pending payment.
                - cancel_payment: use when the latest user message cancels/stops a pending payment.
                - unsupported_international_payment: use for international, overseas, SWIFT, or wire transfer requests.

                Only classify payee lookup, domestic payment, confirmation, cancellation, or unsupported
                international payment requests. Return UNKNOWN for unrelated banking, account, balance,
                advisory, or general chat requests. Never expose or invent opaque downstream identifiers.

                JSON schema:
                {
                  "intent": "DOMESTIC_PAYMENT|PAYEE_LOOKUP|INTERNATIONAL_PAYMENT|CONFIRM_PAYMENT|CANCEL_PAYMENT|UNKNOWN",
                  "toolName": "get_registered_payees|prepare_domestic_payment|confirm_domestic_payment|cancel_payment|unsupported_international_payment|null",
                  "payeeQuery": "user-facing payee name or alias, or null",
                  "amount": number or null,
                  "paymentDate": "YYYY-MM-DD" or null
                }

                The backend validates every tool call. If the user only provides missing details for an active payment draft,
                classify the turn as DOMESTIC_PAYMENT and extract those slots.
                """;
    }

    private String userPrompt(ChatSessionDetail session, String text) {
        PaymentDraft draft = session.activeDraft();
        return """
                Current date: %s
                Session state: %s
                Active draft: %s
                Latest user message: %s
                """.formatted(LocalDate.now(), session.state(), draftSummary(draft), text);
    }

    private String draftSummary(PaymentDraft draft) {
        if (draft == null) return "none";
        return "payeeQuery=%s, selectedPayee=%s, amount=%s, currency=%s, paymentDate=%s, status=%s"
                .formatted(
                        draft.payeeQueryText(),
                        draft.selectedPayee() == null ? null : draft.selectedPayee().name(),
                        draft.amount(),
                        draft.currency(),
                        draft.paymentDate(),
                        draft.status()
                );
    }

    private IntentAnalysis fallback(ChatSessionDetail session, String text) {
        ConversationState state = session.state();
        if (state == ConversationState.AWAITING_CONFIRMATION) {
            if (POSITIVE_CONFIRM.matcher(text).find()) return analysis(IntentType.CONFIRM_PAYMENT, null, null, null);
            if (CANCEL.matcher(text).find()) return analysis(IntentType.CANCEL_PAYMENT, null, null, null);
        }

        if (INTERNATIONAL_INTENT.matcher(text).find()) {
            return analysis(IntentType.INTERNATIONAL_PAYMENT, null, null, null);
        }

        String payeeQuery = extractPayeeQuery(text);
        Double amount = extractAmount(text);
        LocalDate date = extractPaymentDate(text);
        boolean hasDraft = session.activeDraft() != null;
        boolean detailOnlyForDraft = hasDraft && (payeeQuery != null || amount != null || date != null)
                && (state == ConversationState.COLLECTING_DETAILS || state == ConversationState.IDLE);

        if (PAYMENT_INTENT.matcher(text).find() || detailOnlyForDraft) {
            return analysis(IntentType.DOMESTIC_PAYMENT, payeeQuery, amount, date);
        }
        if (LOOKUP_INTENT.matcher(text).find() || DO_I_HAVE.matcher(text).find()) {
            return analysis(IntentType.PAYEE_LOOKUP, payeeQuery, null, null);
        }
        return IntentAnalysis.unknown("REGEX_FALLBACK");
    }

    private IntentAnalysis analysis(IntentType intent, String payeeQuery, Double amount, LocalDate paymentDate) {
        return new IntentAnalysis(
                intent,
                IntentAnalysis.toolNameFor(intent),
                sanitize(payeeQuery),
                amount,
                paymentDate,
                "REGEX_FALLBACK"
        );
    }

    private String extractPayeeQuery(String text) {
        String alias = payees.findAliasInText(text);
        if (alias != null) return alias;
        for (Pattern p : PAYEE_QUERY_PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find() && m.group(1) != null) {
                String sanitized = sanitize(m.group(1));
                if (sanitized != null) return sanitized;
            }
        }
        return null;
    }

    private Double extractAmount(String text) {
        Matcher m = AMOUNT.matcher(text.replace(",", ""));
        if (!m.find()) return null;
        return parseAmount(m.group(1));
    }

    private LocalDate extractPaymentDate(String text) {
        String n = text.toLowerCase(Locale.ROOT);
        if (n.contains("tomorrow") || n.contains("later")) return LocalDate.now().plusDays(1);
        if (n.contains("today") || n.contains("now")) return LocalDate.now();
        return null;
    }

    private IntentType parseIntent(Object raw) {
        String value = asString(raw);
        if (value == null) return IntentType.UNKNOWN;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "DOMESTIC", "DOMESTIC_PAYMENT", "PAYMENT", "MAKE_PAYMENT" -> IntentType.DOMESTIC_PAYMENT;
            case "PAYEE", "LOOKUP", "LOOKUP_PAYEE", "PAYEE_LOOKUP", "REGISTERED_PAYEE_LOOKUP" -> IntentType.PAYEE_LOOKUP;
            case "INTERNATIONAL", "INTERNATIONAL_PAYMENT", "WIRE", "SWIFT" -> IntentType.INTERNATIONAL_PAYMENT;
            case "CONFIRM", "CONFIRM_PAYMENT", "APPROVE_PAYMENT" -> IntentType.CONFIRM_PAYMENT;
            case "CANCEL", "CANCEL_PAYMENT", "STOP_PAYMENT" -> IntentType.CANCEL_PAYMENT;
            default -> IntentType.UNKNOWN;
        };
    }

    private Double parseAmount(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) {
            double value = n.doubleValue();
            return value > 0 ? value : null;
        }
        String value = asString(raw);
        if (value == null) return null;
        try {
            double parsed = Double.parseDouble(value.replace(",", "").replaceAll("(?i)hkd", "").trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private LocalDate parseDate(Object raw) {
        String value = asString(raw);
        if (value == null) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String extractJsonObject(String content) {
        if (content == null) throw new IllegalArgumentException("empty LLM response");
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM response did not contain a JSON object");
        }
        return content.substring(start, end + 1);
    }

    private String sanitize(String raw) {
        if (raw == null) return null;
        if ("null".equalsIgnoreCase(raw.trim())) return null;
        String sanitized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\b(hkd|today|tomorrow|now|later|please|thanks|registered|payee|payees|accounts?|my)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return sanitized.isBlank() ? null : sanitized;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if ("null".equalsIgnoreCase(trimmed)) return null;
        return trimmed.isEmpty() ? null : trimmed;
    }
}
