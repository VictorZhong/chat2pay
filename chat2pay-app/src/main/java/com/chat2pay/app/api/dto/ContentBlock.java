package com.chat2pay.app.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentBlock.TextBlock.class, name = "TEXT"),
        @JsonSubTypes.Type(value = ContentBlock.SummaryCardBlock.class, name = "SUMMARY_CARD"),
        @JsonSubTypes.Type(value = ContentBlock.SelectableListBlock.class, name = "SELECTABLE_LIST"),
        @JsonSubTypes.Type(value = ContentBlock.InfoCardBlock.class, name = "INFO_CARD"),
        @JsonSubTypes.Type(value = ContentBlock.ErrorCardBlock.class, name = "ERROR_CARD"),
})
public sealed interface ContentBlock
        permits ContentBlock.TextBlock,
                ContentBlock.SummaryCardBlock,
                ContentBlock.SelectableListBlock,
                ContentBlock.InfoCardBlock,
                ContentBlock.ErrorCardBlock {

    String blockId();

    String type();

    record TextBlock(
            String blockId,
            String type,
            String title,
            String text,
            Map<String, Object> metadata
    ) implements ContentBlock {
        public TextBlock(String blockId, String title, String text, Map<String, Object> metadata) {
            this(blockId, "TEXT", title, text, metadata);
        }
    }

    record SummaryCardBlock(
            String blockId,
            String type,
            String title,
            List<DisplayField> fields,
            Map<String, Object> metadata
    ) implements ContentBlock {
        public SummaryCardBlock(String blockId, String title, List<DisplayField> fields, Map<String, Object> metadata) {
            this(blockId, "SUMMARY_CARD", title, fields, metadata);
        }
    }

    record SelectableListBlock(
            String blockId,
            String type,
            String title,
            List<SelectableItem> items,
            Map<String, Object> metadata
    ) implements ContentBlock {
        public SelectableListBlock(String blockId, String title, List<SelectableItem> items, Map<String, Object> metadata) {
            this(blockId, "SELECTABLE_LIST", title, items, metadata);
        }
    }

    record InfoCardBlock(
            String blockId,
            String type,
            String title,
            String text,
            Map<String, Object> metadata
    ) implements ContentBlock {
        public InfoCardBlock(String blockId, String title, String text, Map<String, Object> metadata) {
            this(blockId, "INFO_CARD", title, text, metadata);
        }
    }

    record ErrorCardBlock(
            String blockId,
            String type,
            String title,
            String text,
            Map<String, Object> metadata
    ) implements ContentBlock {
        public ErrorCardBlock(String blockId, String title, String text, Map<String, Object> metadata) {
            this(blockId, "ERROR_CARD", title, text, metadata);
        }
    }

    record DisplayField(String label, String value) {}

    record SelectableItem(
            String itemId,
            String label,
            String description,
            String value,
            Map<String, Object> metadata
    ) {}
}
