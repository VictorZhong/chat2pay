package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.persistence.entity.RegisteredPayeeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegisteredPayeeRepository extends JpaRepository<RegisteredPayeeEntity, String> {

    List<RegisteredPayeeEntity> findByProfileIdOrderByNameAscAccountNumberAsc(String profileId);

    Optional<RegisteredPayeeEntity> findByProfileIdAndId(String profileId, String id);
}
