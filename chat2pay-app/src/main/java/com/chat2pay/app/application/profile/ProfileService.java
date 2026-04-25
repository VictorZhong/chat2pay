package com.chat2pay.app.application.profile;

import com.chat2pay.app.api.dto.ProfileDtos.CurrentUserContext;
import com.chat2pay.app.api.dto.ProfileDtos.ProfileLoginRequest;
import com.chat2pay.app.api.dto.ProfileDtos.ProfileSummary;
import com.chat2pay.app.persistence.repository.ProfileStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Owns demo-profile listing and fixed POC access login flow.
 */
@Service
public class ProfileService {

    private static final String POC_ACCESS_PASSWORD = "tb123";

    private final ProfileStore profileStore;

    public ProfileService(ProfileStore profileStore) {
        this.profileStore = profileStore;
    }

    public List<ProfileSummary> list() {
        return profileStore.list();
    }

    public CurrentUserContext login(ProfileLoginRequest request) {
        ProfileSummary profile = profileStore.findById(request.profileId())
                .orElseThrow(() -> new NoSuchElementException("Profile not found: " + request.profileId()));
        if (!POC_ACCESS_PASSWORD.equals(request.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid POC access password");
        }
        return new CurrentUserContext(
                profile.id(),
                profile.username(),
                profile.displayName(),
                profile.avatarUrl(),
                profile.locale(),
                "PROFILE_SELECTION",
                profile.supportedCapabilities()
        );
    }
}
