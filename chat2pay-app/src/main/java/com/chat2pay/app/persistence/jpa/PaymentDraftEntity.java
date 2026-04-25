package com.chat2pay.app.persistence.jpa;

import com.chat2pay.app.domain.payment.PaymentDraftStatus;
import com.chat2pay.app.domain.payment.PaymentType;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "ctp_payment_draft")
@Getter
@Setter
public class PaymentDraftEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "session_id", length = 64, nullable = false, unique = true)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", length = 32, nullable = false)
    private PaymentType paymentType;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private PaymentDraftStatus status;

    @Column(name = "payee_query_text", length = 160)
    private String payeeQueryText;

    @Column(name = "selected_payee_id", length = 128)
    private String selectedPayeeId;

    @Column(name = "selected_payee_name", length = 160)
    private String selectedPayeeName;

    @Column(name = "selected_payee_type", length = 32)
    private String selectedPayeeType;

    @Column(name = "selected_bank_code", length = 32)
    private String selectedBankCode;

    @Column(name = "selected_bank_name", length = 160)
    private String selectedBankName;

    @Column(name = "selected_account_number", length = 64)
    private String selectedAccountNumber;

    @Column(name = "selected_display_label", length = 160)
    private String selectedDisplayLabel;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "user_confirmed_at")
    private Instant userConfirmedAt;

    @Column(name = "downstream_reference", length = 128)
    private String downstreamReference;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "text")
    private String lastErrorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json", columnDefinition = "jsonb")
    private Map<String, Object> context = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}
