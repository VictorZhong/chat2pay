package com.chat2pay.app.persistence.memory;

import com.chat2pay.app.api.dto.ProfileDtos.ProfileSummary;
import com.chat2pay.app.domain.profile.CapabilityType;
import com.chat2pay.app.domain.profile.ProfileStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class InMemoryProfileStore {

    public static final String SHARED_PASSWORD = "tb123";

    private static final List<CapabilityType> DEFAULT_CAPS =
            List.of(CapabilityType.REGISTERED_PAYEE_LOOKUP, CapabilityType.DOMESTIC_PAYMENT);

    private final List<ProfileSummary> profiles = List.of(
            new ProfileSummary("profile_victor", "HK_STAFF_001", "payment10", "Victor Zhong",
                    null, "en-HK", ProfileStatus.ACTIVE, DEFAULT_CAPS),
            new ProfileSummary("profile_iris", "HK_OPS_014", "payment14", "Iris Leung",
                    null, "en-HK", ProfileStatus.ACTIVE, DEFAULT_CAPS),
            new ProfileSummary("profile_marcus", "HK_FIN_021", "payment21", "Marcus Ng",
                    null, "en-HK", ProfileStatus.ACTIVE, DEFAULT_CAPS)
    );

    public List<ProfileSummary> list() {
        return profiles;
    }

    public Optional<ProfileSummary> findById(String profileId) {
        return profiles.stream().filter(p -> p.id().equals(profileId)).findFirst();
    }
}
