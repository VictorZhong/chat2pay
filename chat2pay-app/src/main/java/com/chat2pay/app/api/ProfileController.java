package com.chat2pay.app.api;

import com.chat2pay.app.api.dto.ProfileDtos.CurrentUserContext;
import com.chat2pay.app.api.dto.ProfileDtos.ProfileLoginRequest;
import com.chat2pay.app.api.dto.ProfileDtos.ProfileSummary;
import com.chat2pay.app.application.profile.ProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/profiles")
    public List<ProfileSummary> listProfiles() {
        return profileService.list();
    }

    @PostMapping("/auth/profile-login")
    public CurrentUserContext login(@Valid @RequestBody ProfileLoginRequest request) {
        return profileService.login(request);
    }
}
