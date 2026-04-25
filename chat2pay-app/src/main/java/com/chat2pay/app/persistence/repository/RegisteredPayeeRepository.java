package com.chat2pay.app.persistence.repository;

import com.chat2pay.app.persistence.entity.RegisteredPayeeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RegisteredPayeeRepository extends JpaRepository<RegisteredPayeeEntity, String> {

    List<RegisteredPayeeEntity> findAllByOrderByNameAscAccountNumberAsc();
}
