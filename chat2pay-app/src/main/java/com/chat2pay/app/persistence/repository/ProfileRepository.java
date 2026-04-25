package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.domain.profile.ProfileStatus;
import com.chat2pay.app.persistence.entity.ProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProfileRepository extends JpaRepository<ProfileEntity, String> {

    List<ProfileEntity> findByStatusOrderByDisplayName(ProfileStatus status);
}
