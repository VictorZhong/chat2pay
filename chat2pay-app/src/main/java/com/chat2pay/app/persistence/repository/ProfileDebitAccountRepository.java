package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.persistence.entity.ProfileDebitAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProfileDebitAccountRepository extends JpaRepository<ProfileDebitAccountEntity, String> {

    List<ProfileDebitAccountEntity> findByProfileIdOrderBySortOrderAscCreatedAtAsc(String profileId);

    Optional<ProfileDebitAccountEntity> findByIdAndProfileId(String id, String profileId);
}
