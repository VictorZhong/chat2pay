package com.chat2pay.app.application.chat;

import com.chat2pay.app.api.ApiModels;
import java.util.List;

public record TurnOutcome(
        String assistantText,
        List<ApiModels.ContentBlock> assistantBlocks,
        List<ApiModels.SuggestedActionResponse> suggestedActions) {

    public static TurnOutcome of(List<ApiModels.ContentBlock> blocks) {
        return new TurnOutcome(null, blocks, List.of());
    }

    public static TurnOutcome of(
            List<ApiModels.ContentBlock> blocks,
            List<ApiModels.SuggestedActionResponse> suggestedActions) {
        return new TurnOutcome(null, blocks, suggestedActions);
    }
}
