package com.chat2pay.app.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "ctp_profile_debit_account")
@Getter
@Setter
public class ProfileDebitAccountEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "profile_id", length = 64, nullable = false)
    private String profileId;

    @Column(name = "account_number", columnDefinition = "text", nullable = false)
    private String accountNumber;

    @Column(name = "product_category_code", length = 32)
    private String productCategoryCode;

    @Column(name = "display_label", columnDefinition = "text")
    private String displayLabel;

    @Column(length = 3)
    private String currency;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
