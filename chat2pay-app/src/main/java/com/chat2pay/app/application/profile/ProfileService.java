package com.chat2pay.app.application.profile;

import com.chat2pay.app.api.ApiModels;
import com.chat2pay.app.application.chat.ApiMapper;
import com.chat2pay.app.common.ApiException;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.Profile;
import com.chat2pay.app.domain.ProfileStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {

    private final ApiMapper apiMapper;
    private final Chat2PayProperties properties;
    private final Map<String, Profile> profilesById;

    public ProfileService(ApiMapper apiMapper, Chat2PayProperties properties) {
        this.apiMapper = apiMapper;
        this.properties = properties;
        this.profilesById = buildProfileMap(properties);
    }

    public List<ApiModels.ProfileSummaryResponse> listProfiles() {
        return profilesById.values().stream()
                .filter(profile -> profile.status() == ProfileStatus.ACTIVE)
                .map(apiMapper::toProfileSummary)
                .toList();
    }

    public ApiModels.CurrentUserContextResponse login(ApiModels.ProfileLoginRequest request) {
        Profile profile = requireProfile(request.profileId());
        if (!properties.getSecurity().getSharedPassword().equals(request.password())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CHAT2PAY-401", "Shared password is incorrect.");
        }
        return apiMapper.toCurrentUser(profile);
    }

    public ApiModels.CurrentUserContextResponse requireCurrentUser(String profileId) {
        return apiMapper.toCurrentUser(requireProfile(profileId));
    }

    public Profile requireProfile(String profileId) {
        Profile profile = profilesById.get(profileId);
        if (profile == null || profile.status() != ProfileStatus.ACTIVE) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAT2PAY-404", "Profile not found.");
        }
        return profile;
    }

    private Map<String, Profile> buildProfileMap(Chat2PayProperties properties) {
        Map<String, Profile> profiles = new LinkedHashMap<>();
        for (Chat2PayProperties.ProfileConfig profile : properties.getDemo().getProfiles()) {
            profiles.put(profile.getId(), new Profile(
                    profile.getId(),
                    profile.getCode(),
                    profile.getUsername(),
                    profile.getDisplayName(),
                    profile.getAvatarUrl(),
                    profile.getMockCustomerId(),
                    profile.getLocale(),
                    profile.getStatus(),
                    List.copyOf(profile.getSupportedJourneyTypes())));
        }
        return Map.copyOf(profiles);
    }
}
