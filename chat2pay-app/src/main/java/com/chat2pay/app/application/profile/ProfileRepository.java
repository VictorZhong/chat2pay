package com.chat2pay.app.application.profile;

import com.chat2pay.app.domain.Profile;
import java.util.List;
import java.util.Optional;

public interface ProfileRepository {

    List<Profile> findAllActive();

    Optional<Profile> findActiveById(String profileId);
}
