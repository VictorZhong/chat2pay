package com.chat2pay.app.application.chat;

import com.chat2pay.app.api.ApiModels;
import com.chat2pay.app.common.UlidFactory;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BlockFactory {

    private final UlidFactory ulidFactory;

    public BlockFactory(UlidFactory ulidFactory) {
        this.ulidFactory = ulidFactory;
    }

    public ApiModels.TextBlock text(String text) {
        return new ApiModels.TextBlock(
                ulidFactory.nextPrefixed("blk_text"),
                "TEXT",
                null,
                text,
                Map.of());
    }

    public ApiModels.TextBlock text(String title, String text) {
        return new ApiModels.TextBlock(
                ulidFactory.nextPrefixed("blk_text"),
                "TEXT",
                title,
                text,
                Map.of());
    }

    public ApiModels.InfoCardBlock info(String title, String text) {
        return new ApiModels.InfoCardBlock(
                ulidFactory.nextPrefixed("blk_info"),
                "INFO_CARD",
                title,
                text,
                Map.of());
    }

    public ApiModels.ErrorCardBlock error(String title, String text) {
        return new ApiModels.ErrorCardBlock(
                ulidFactory.nextPrefixed("blk_error"),
                "ERROR_CARD",
                title,
                text,
                Map.of());
    }

    public ApiModels.SummaryCardBlock summary(
            String title,
            List<ApiModels.DisplayField> fields,
            Map<String, Object> metadata) {
        return new ApiModels.SummaryCardBlock(
                ulidFactory.nextPrefixed("blk_summary"),
                "SUMMARY_CARD",
                title,
                fields,
                metadata);
    }

    public ApiModels.SelectableListBlock selectableList(
            String title,
            List<ApiModels.SelectableItem> items,
            Map<String, Object> metadata) {
        return new ApiModels.SelectableListBlock(
                ulidFactory.nextPrefixed("blk_list"),
                "SELECTABLE_LIST",
                title,
                "SINGLE",
                items,
                metadata);
    }

    public ApiModels.SimpleFormBlock simpleForm(
            String title,
            List<ApiModels.FormField> fields,
            String submitLabel,
            Map<String, Object> metadata) {
        return new ApiModels.SimpleFormBlock(
                ulidFactory.nextPrefixed("blk_form"),
                "SIMPLE_FORM",
                title,
                fields,
                submitLabel,
                metadata);
    }
}
