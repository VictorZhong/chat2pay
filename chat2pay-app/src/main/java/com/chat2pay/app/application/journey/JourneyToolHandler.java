package com.chat2pay.app.application.journey;

public interface JourneyToolHandler {

    JourneyToolDefinition definition();

    JourneyToolResult execute(JourneyToolExecutionContext context);
}
