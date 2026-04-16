package com.chat2pay.app.application.journey;

public interface JourneyAgentPlanner {

    JourneyAgentDecision plan(JourneyAgentContext context);
}
