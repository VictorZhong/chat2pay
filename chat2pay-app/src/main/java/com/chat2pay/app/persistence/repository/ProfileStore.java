package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.api.dto.ProfileDtos.ProfileSummary;
import com.chat2pay.app.domain.profile.ProfileStatus;
import com.chat2pay.app.persistence.entity.ProfileEntity;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Profile lookup backed by ctp_profile. Profiles are inserted manually for the
 * POC; this store is read-only from the application perspective.
 */
@Repository
public class ProfileStore {

    private static final Duration RUNTIME_CACHE_TTL = Duration.ofDays(1);

    private final ProfileRepository profiles;
    private final Map<String, CachedRuntimeProfile> runtimeCache = new HashMap<>();

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

    public RuntimeProfile runtimeProfile(String profileId) {
        Instant now = Instant.now();
        synchronized (runtimeCache) {
            CachedRuntimeProfile cached = runtimeCache.get(profileId);
            if (cached != null && cached.cachedAt().plus(RUNTIME_CACHE_TTL).isAfter(now)) {
                return cached.profile();
            }
            RuntimeProfile loaded = profiles.findById(profileId)
                    .map(this::mapRuntime)
                    .orElseThrow(() -> new NoSuchElementException("Profile not found: " + profileId));
            runtimeCache.put(profileId, new CachedRuntimeProfile(loaded, now));
            return loaded;
        }
    }

    private ProfileSummary map(ProfileEntity p) {
        return new ProfileSummary(
                p.getId(),
                p.getGuid(),
                p.getPermNetId(),
                p.getProfileCode(),
                p.getUsername(),
                p.getDisplayName(),
                p.getAvatarUrl(),
                p.getLocale(),
                p.getStatus(),
                p.getSupportedCapabilities() == null ? List.of() : List.copyOf(p.getSupportedCapabilities())
        );
    }

    private RuntimeProfile mapRuntime(ProfileEntity p) {
        return new RuntimeProfile(
                p.getId(),
                p.getGuid(),
                p.getPermNetId(),
                p.getUsername(),
                p.getPassword(),
                p.getDebitAccountNumber(),
                p.getDebitProductCategoryCode(),
                p.getPaymentCurrency()
        );
    }

    private record CachedRuntimeProfile(RuntimeProfile profile, Instant cachedAt) {}

    public record RuntimeProfile(
            String profileId,
            String guid,
            String permNetId,
            String username,
            String password,
            String debitAccountNumber,
            String debitProductCategoryCode,
            String paymentCurrency
    ) {
        public String requiredUsername() {
            if (username == null || username.isBlank()) {
                throw new IllegalStateException("Profile " + profileId + " has no username configured.");
            }
            return username;
        }

        public String requiredPassword() {
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("Profile " + profileId + " has no downstream password configured.");
            }
            return password;
        }

        public String requiredDebitAccountNumber() {
            if (debitAccountNumber == null || debitAccountNumber.isBlank()) {
                throw new IllegalStateException("Profile " + profileId + " has no debit account number configured.");
            }
            return debitAccountNumber;
        }

        public String debitProductCategoryCodeOrDefault() {
            return debitProductCategoryCode == null || debitProductCategoryCode.isBlank()
                    ? "CUR" : debitProductCategoryCode;
        }

        public String paymentCurrencyOrDefault(String defaultCurrency) {
            if (paymentCurrency != null && !paymentCurrency.isBlank()) return paymentCurrency;
            return defaultCurrency == null || defaultCurrency.isBlank() ? "HKD" : defaultCurrency;
        }

        public String sourceSystemIdOrDefault(String defaultSourceSystemId) {
            if (permNetId != null && !permNetId.isBlank()) return permNetId;
            return defaultSourceSystemId;
        }
    }
}
