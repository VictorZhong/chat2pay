package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.MessageAnalysis;
import java.util.Map;

public record JourneyUserSignal(
        String rawMessage,
        MessageAnalysis analysis,
        String clickedActionId,
        String selectedItemId,
        Map<String, String> formValues) {

    public boolean hasStructuredFormInput() {
        return formValues != null && !formValues.isEmpty();
    }

    public boolean hasStartedJourneySignal() {
        return hasStructuredFormInput()
                || selectedItemId != null
                || clickedActionId != null
                || (analysis != null && analysis.supportedPaymentIntent());
    }

    public boolean explicitConfirmationRequested() {
        return "CONFIRM_TRANSFER".equals(clickedActionId)
                || (analysis != null && analysis.confirmIntent());
    }

    public boolean explicitCancellationRequested() {
        return "CANCEL_TRANSFER".equals(clickedActionId)
                || (analysis != null && analysis.cancelIntent());
    }
}
