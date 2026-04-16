package com.chat2pay.app.api;

import com.chat2pay.app.application.profile.ProfileService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProfileApiController {

    private final ProfileService profileService;

    public ProfileApiController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/profiles")
    public List<ApiModels.ProfileSummaryResponse> listProfiles() {
        return profileService.listProfiles();
    }

    @PostMapping("/auth/profile-login")
    public ApiModels.CurrentUserContextResponse profileLogin(
            @Valid @RequestBody ApiModels.ProfileLoginRequest request) {
        return profileService.login(request);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@RequestHeader("X-Profile-Id") String profileId) {
        profileService.requireCurrentUser(profileId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ApiModels.CurrentUserContextResponse currentUser(@RequestHeader("X-Profile-Id") String profileId) {
        return profileService.requireCurrentUser(profileId);
    }
}
