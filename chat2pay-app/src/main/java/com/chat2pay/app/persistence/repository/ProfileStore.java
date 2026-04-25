package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.api.dto.ProfileDtos.ProfileSummary;
import com.chat2pay.app.domain.profile.ProfileStatus;
import com.chat2pay.app.persistence.entity.ProfileEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Profile lookup backed by ctp_profile. Profiles are inserted manually for the
 * POC; this store is read-only from the application perspective.
 */
@Repository
public class ProfileStore {

    public static final String SHARED_PASSWORD = "tb123";

    private final ProfileRepository profiles;

    public ProfileStore(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    public List<ProfileSummary> list() {
        return profiles.findByStatusOrderByDisplayName(ProfileStatus.ACTIVE).stream()
                .map(this::map)
                .toList();
    }

    public Optional<ProfileSummary> findById(String profileId) {
        return profiles.findById(profileId).map(this::map);
    }

    private ProfileSummary map(ProfileEntity p) {
        return new ProfileSummary(
                p.getId(),
                p.getProfileCode(),
                p.getUsername(),
                p.getDisplayName(),
                p.getAvatarUrl(),
                p.getLocale(),
                p.getStatus(),
                p.getSupportedCapabilities() == null ? List.of() : List.copyOf(p.getSupportedCapabilities())
        );
    }
}
