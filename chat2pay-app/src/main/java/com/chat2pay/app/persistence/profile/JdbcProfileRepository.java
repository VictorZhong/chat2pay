package com.chat2pay.app.persistence.profile;

import com.chat2pay.app.application.profile.ProfileRepository;
import com.chat2pay.app.domain.JourneyType;
import com.chat2pay.app.domain.Profile;
import com.chat2pay.app.domain.ProfileStatus;
import com.chat2pay.app.persistence.support.JsonCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProfileRepository implements ProfileRepository {

    private static final TypeReference<List<JourneyType>> JOURNEY_TYPE_LIST = new TypeReference<>() {
    };

    private static final String SELECT_ACTIVE_PROFILES = """
            select
                id,
                profile_code,
                username,
                display_name,
                avatar_url,
                mock_customer_id,
                locale,
                supported_journey_types_json,
                status
            from ctp_profile
            where status = 'ACTIVE'
            order by created_at asc, id asc
            """;

    private static final String SELECT_ACTIVE_PROFILE_BY_ID = """
            select
                id,
                profile_code,
                username,
                display_name,
                avatar_url,
                mock_customer_id,
                locale,
                supported_journey_types_json,
                status
            from ctp_profile
            where id = :profileId
              and status = 'ACTIVE'
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonCodec jsonCodec;

    public JdbcProfileRepository(NamedParameterJdbcTemplate jdbcTemplate, JsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public List<Profile> findAllActive() {
        return jdbcTemplate.query(SELECT_ACTIVE_PROFILES, this::mapProfile);
    }

    @Override
    public Optional<Profile> findActiveById(String profileId) {
        List<Profile> profiles = jdbcTemplate.query(
                SELECT_ACTIVE_PROFILE_BY_ID,
                new MapSqlParameterSource("profileId", profileId),
                this::mapProfile);
        return profiles.stream().findFirst();
    }

    private Profile mapProfile(ResultSet resultSet, int rowNum) throws SQLException {
        return new Profile(
                resultSet.getString("id"),
                resultSet.getString("profile_code"),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getString("avatar_url"),
                resultSet.getString("mock_customer_id"),
                resultSet.getString("locale"),
                ProfileStatus.valueOf(resultSet.getString("status")),
                List.copyOf(jsonCodec.read(
                        resultSet.getString("supported_journey_types_json"),
                        JOURNEY_TYPE_LIST,
                        List::of)));
    }
}
