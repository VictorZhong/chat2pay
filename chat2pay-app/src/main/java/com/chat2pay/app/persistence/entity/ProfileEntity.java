package com.chat2pay.app.persistence.entity;

import com.chat2pay.app.domain.profile.CapabilityType;
import com.chat2pay.app.domain.profile.ProfileStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ctp_profile")
@Getter
@Setter
public class ProfileEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "profile_code", length = 64, nullable = false, unique = true)
    private String profileCode;

    @Column(length = 64, unique = true)
    private String guid;

    @Column(name = "perm_net_id", length = 128, unique = true)
    private String permNetId;

    @Column(length = 128, nullable = false, unique = true)
    private String username;

    @Column(columnDefinition = "text")
    private String password;

    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;

    @Column(name = "avatar_url", length = 256)
    private String avatarUrl;

    @Column(length = 16, nullable = false)
    private String locale = "en-HK";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supported_capabilities_json", nullable = false, columnDefinition = "jsonb")
    private List<CapabilityType> supportedCapabilities = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private ProfileStatus status;

    @Column(name = "debit_account_number", columnDefinition = "text")
    private String debitAccountNumber;

    @Column(name = "debit_product_category_code", length = 16)
    private String debitProductCategoryCode;

    @Column(name = "payment_currency", length = 3)
    private String paymentCurrency;

    @Column(name = "source_system_id", length = 128)
    private String sourceSystemId;

    @Column(name = "payee_source_system_id", length = 128)
    private String payeeSourceSystemId;

    @Column(name = "domestic_payment_source_system_id", length = 128)
    private String domesticPaymentSourceSystemId;

    @Column(name = "cross_border_payment_source_system_id", length = 128)
    private String crossBorderPaymentSourceSystemId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
