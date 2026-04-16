package com.chat2pay.app.domain;

import java.util.List;

public record Profile(
        String id,
        String code,
        String username,
        String displayName,
        String avatarUrl,
        String mockCustomerId,
        String locale,
        ProfileStatus status,
        List<JourneyType> supportedJourneyTypes) {
}
