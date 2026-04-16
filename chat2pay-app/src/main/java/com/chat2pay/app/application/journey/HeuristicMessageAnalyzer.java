package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.MessageAnalysis;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class HeuristicMessageAnalyzer {

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(\\d[\\d,]*(?:\\.\\d{1,2})?)");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("\\b(HKD|USD|SGD|CNY)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYEE_PATTERN = Pattern.compile(
            "\\b(?:pay|send|transfer(?:\\s+to)?|remit(?:\\s+to)?)\\s+(.+?)(?=\\s+\\d[\\d,]*(?:\\.\\d{1,2})?(?:\\s+[A-Za-z]{3})?\\b|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTE_PATTERN = Pattern.compile("\\b(?:for|note|remark)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPORTED_INTENT_PATTERN = Pattern.compile("\\b(pay|send|transfer|remit)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIRM_PATTERN = Pattern.compile("\\b(confirm|approve|go ahead)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANCEL_PATTERN = Pattern.compile("\\b(cancel|stop|abort)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSUPPORTED_PATTERN = Pattern.compile(
            "\\b(international|overseas|abroad|swift|fx|foreign|new payee|add payee|register payee)\\b",
            Pattern.CASE_INSENSITIVE);

    public MessageAnalysis analyze(String message) {
        String normalizedMessage = message == null ? "" : message.trim();
        BigDecimal amount = parseAmount(normalizedMessage);
        String currency = parseCurrency(normalizedMessage);
        if (amount != null && currency == null) {
            currency = "HKD";
        }

        return new MessageAnalysis(
                SUPPORTED_INTENT_PATTERN.matcher(normalizedMessage).find(),
                UNSUPPORTED_PATTERN.matcher(normalizedMessage).find(),
                CONFIRM_PATTERN.matcher(normalizedMessage).find(),
                CANCEL_PATTERN.matcher(normalizedMessage).find(),
                parsePayeeName(normalizedMessage),
                amount,
                currency,
                parseNote(normalizedMessage));
    }

    private BigDecimal parseAmount(String text) {
        Matcher matcher = AMOUNT_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return new BigDecimal(matcher.group(1).replace(",", ""));
    }

    private String parseCurrency(String text) {
        Matcher matcher = CURRENCY_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1).toUpperCase() : null;
    }

    private String parsePayeeName(String text) {
        Matcher matcher = PAYEE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String candidate = matcher.group(1)
                .replaceAll("\\bto\\b", "")
                .trim();
        return candidate.isBlank() ? null : candidate;
    }

    private String parseNote(String text) {
        Matcher matcher = NOTE_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
