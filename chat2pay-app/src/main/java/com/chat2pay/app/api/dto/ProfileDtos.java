package com.chat2pay.app.api.dto;

import com.chat2pay.app.domain.profile.CapabilityType;
import com.chat2pay.app.domain.profile.ProfileStatus;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class ProfileDtos {
    private ProfileDtos() {}

    public record ProfileSummary(
            String id,
            String code,
            String username,
            String displayName,
            String avatarUrl,
            String locale,
            ProfileStatus status,
            List<CapabilityType> supportedCapabilities
    ) {}

    public record ProfileLoginRequest(
            @NotBlank String profileId,
            @NotBlank String password
    ) {}

    public record CurrentUserContext(
            String profileId,
            String username,
            String displayName,
            String avatarUrl,
            String locale,
            String loginMode,
            List<CapabilityType> supportedCapabilities
    ) {}
}
