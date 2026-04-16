package com.chat2pay.app.application.journey;

public record JourneyToolDefinition(
        String name,
        String description,
        String constraints,
        boolean requiresDraft) {
}
