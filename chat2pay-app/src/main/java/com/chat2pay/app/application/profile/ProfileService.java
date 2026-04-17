package com.chat2pay.app.application.profile;

import com.chat2pay.app.api.ApiModels;
import com.chat2pay.app.application.chat.ApiMapper;
import com.chat2pay.app.common.ApiException;
import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.Profile;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {

    private final ApiMapper apiMapper;
    private final Chat2PayProperties properties;
    private final ProfileRepository profileRepository;

    public ProfileService(ApiMapper apiMapper, Chat2PayProperties properties, ProfileRepository profileRepository) {
        this.apiMapper = apiMapper;
        this.properties = properties;
        this.profileRepository = profileRepository;
    }

    public List<ApiModels.ProfileSummaryResponse> listProfiles() {
        return profileRepository.findAllActive().stream()
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
        return profileRepository.findActiveById(profileId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CHAT2PAY-404", "Profile not found."));
    }
}
