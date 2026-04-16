package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.MessageAnalysis;
import com.chat2pay.app.domain.Profile;

public interface LlmProvider {

    MessageAnalysis analyze(Profile profile, String userMessage);
}
