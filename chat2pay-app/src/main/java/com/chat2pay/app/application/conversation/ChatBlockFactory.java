package com.chat2pay.app.application.conversation;

import com.chat2pay.app.api.dto.ChatDtos.ChatMessage;
import com.chat2pay.app.api.dto.ChatDtos.PaymentDraft;
import com.chat2pay.app.api.dto.ContentBlock;
import com.chat2pay.app.api.dto.ContentBlock.DisplayField;
import com.chat2pay.app.domain.conversation.MessageKind;
import com.chat2pay.app.domain.conversation.MessageRole;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builders for the structured content blocks the chat UI renders. Pulled out
 * of {@link ChatOrchestratorService} so block shape changes do not force a
 * rebuild of the orchestration logic.
 */
@Component
public class ChatBlockFactory {

    static final String DEFAULT_CURRENCY = "HKD";

    public ContentBlock.TextBlock textBlock(String title, String text) {
        return new ContentBlock.TextBlock(newBlockId("text"), title, text, null);
    }

    public ContentBlock.InfoCardBlock infoBlock(String title, String text) {
        return new ContentBlock.InfoCardBlock(newBlockId("info"), title, text, null);
    }

    public ContentBlock.ErrorCardBlock errorBlock(String title, String text) {
        return new ContentBlock.ErrorCardBlock(newBlockId("error"), title, text, null);
    }

    public ContentBlock.SummaryCardBlock summaryBlock(String title,
                                                      List<DisplayField> fields,
                                                      Map<String, Object> metadata) {
        return new ContentBlock.SummaryCardBlock(newBlockId("summary"), title, fields, metadata);
    }

    public ContentBlock.SelectableListBlock selectableListBlock(String title,
                                                                List<ContentBlock.SelectableItem> items,
                                                                Map<String, Object> metadata) {
        return new ContentBlock.SelectableListBlock(newBlockId("list"), title, items, metadata);
    }

    public ChatMessage assistantMessage(String sessionId, List<ContentBlock> blocks) {
        return new ChatMessage(newMessageId(), sessionId, MessageRole.ASSISTANT,
                blocks.isEmpty() ? MessageKind.TEXT : MessageKind.BLOCKS,
                null, blocks.isEmpty() ? null : blocks, null, Instant.now());
    }

    public ChatMessage userTextMessage(String sessionId, String text) {
        return new ChatMessage(newMessageId(), sessionId, MessageRole.USER, MessageKind.TEXT,
                text, null, null, Instant.now());
    }

    public List<DisplayField> draftFields(PaymentDraft d) {
        String amount = d.amount() != null
                ? String.format(Locale.ROOT, "%s %,.2f",
                        d.currency() != null ? d.currency() : DEFAULT_CURRENCY,
                        d.amount().setScale(2, RoundingMode.HALF_UP))
                : "Pending";
        return List.of(
                new DisplayField("Payee", d.selectedPayee() != null ? d.selectedPayee().name()
                        : d.payeeQueryText() != null ? d.payeeQueryText() : "Pending"),
                new DisplayField("Account", d.selectedPayee() != null ? d.selectedPayee().displayLabel() : "Pending"),
                new DisplayField("Bank", d.selectedPayee() != null ? d.selectedPayee().bankName() : "Pending"),
                new DisplayField("Amount", amount),
                new DisplayField("Payment date", d.paymentDate() != null ? d.paymentDate().toString() : "Pending")
        );
    }

    public static String newMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    public static String newBlockId(String kind) {
        return "blk_" + kind + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
