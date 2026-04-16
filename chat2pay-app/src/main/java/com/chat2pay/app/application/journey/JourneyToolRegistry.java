package com.chat2pay.app.application.journey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JourneyToolRegistry {

    private final Map<String, JourneyToolHandler> handlersByName;

    public JourneyToolRegistry(List<JourneyToolHandler> handlers) {
        LinkedHashMap<String, JourneyToolHandler> indexedHandlers = new LinkedHashMap<>();
        for (JourneyToolHandler handler : handlers) {
            String toolName = handler.definition().name();
            JourneyToolHandler duplicate = indexedHandlers.putIfAbsent(toolName, handler);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate journey tool handler registered for " + toolName);
            }
        }
        this.handlersByName = Collections.unmodifiableMap(indexedHandlers);
    }

    public List<JourneyToolDefinition> availableTools() {
        return handlersByName.values().stream()
                .map(JourneyToolHandler::definition)
                .toList();
    }

    public boolean requiresDraft(String toolName) {
        JourneyToolHandler handler = handlersByName.get(toolName);
        return handler != null && handler.definition().requiresDraft();
    }

    public JourneyToolResult execute(String toolName, JourneyToolExecutionContext context) {
        JourneyToolHandler handler = handlersByName.get(toolName);
        if (handler == null) {
            return JourneyToolResult.failure(toolName, "Unknown tool requested: " + toolName);
        }
        return handler.execute(context);
    }
}
