package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.persistence.entity.PaymentDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentDraftRepository extends JpaRepository<PaymentDraftEntity, String> {

    Optional<PaymentDraftEntity> findBySessionId(String sessionId);
}
