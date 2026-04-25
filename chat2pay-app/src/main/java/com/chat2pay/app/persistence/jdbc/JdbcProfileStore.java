package com.chat2pay.app.persistence.jdbc;

import com.chat2pay.app.api.dto.ProfileDtos.ProfileSummary;
import com.chat2pay.app.domain.profile.CapabilityType;
import com.chat2pay.app.domain.profile.ProfileStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * Profile lookup backed by ctp_profile. Profiles are inserted manually for
 * the POC; this store is read-only.
 */
@Repository
public class JdbcProfileStore {

    public static final String SHARED_PASSWORD = "tb123";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcProfileStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<ProfileSummary> list() {
        return jdbc.sql("""
                select id, profile_code, username, display_name, avatar_url, locale,
                       supported_capabilities_json::text as caps_json, status
                  from ctp_profile
                 where status = 'ACTIVE'
                 order by display_name
                """)
                .query((rs, rowNum) -> mapRow(rs))
                .list();
    }

    public Optional<ProfileSummary> findById(String profileId) {
        return jdbc.sql("""
                select id, profile_code, username, display_name, avatar_url, locale,
                       supported_capabilities_json::text as caps_json, status
                  from ctp_profile
                 where id = :id
                """)
                .param("id", profileId)
                .query((rs, rowNum) -> mapRow(rs))
                .optional();
    }

    private ProfileSummary mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String capsJson = rs.getString("caps_json");
        List<CapabilityType> caps;
        try {
            caps = capsJson == null ? List.of()
                    : mapper.readValue(capsJson, new TypeReference<List<CapabilityType>>() {});
        } catch (Exception e) {
            throw new UncheckedIOException(new java.io.IOException("Failed to read capabilities JSON", e));
        }
        return new ProfileSummary(
                rs.getString("id"),
                rs.getString("profile_code"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("avatar_url"),
                rs.getString("locale"),
                ProfileStatus.valueOf(rs.getString("status")),
                caps
        );
    }
}
