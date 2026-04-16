package com.chat2pay.app.domain;

import com.chat2pay.app.integration.downstream.RegisteredPayee;
import java.util.List;

public record PayeeMatchResult(List<RegisteredPayee> matches) {

    public boolean isNone() {
        return matches.isEmpty();
    }

    public boolean isUnique() {
        return matches.size() == 1;
    }

    public boolean isAmbiguous() {
        return matches.size() > 1;
    }

    public RegisteredPayee uniqueMatch() {
        return matches.getFirst();
    }
}
