package com.chat2pay.app.persistence.jpa;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ctp_registered_payee")
@Getter
@Setter
public class RegisteredPayeeEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 160, nullable = false)
    private String name;

    @Column(name = "payee_type", length = 32, nullable = false)
    private String payeeType;

    @Column(name = "bank_code", length = 32, nullable = false)
    private String bankCode;

    @Column(name = "bank_name", length = 160, nullable = false)
    private String bankName;

    @Column(name = "account_number", length = 64, nullable = false)
    private String accountNumber;

    @Column(name = "display_label", length = 160, nullable = false)
    private String displayLabel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ctp_payee_alias", joinColumns = @JoinColumn(name = "payee_id"))
    @Column(name = "alias", length = 160, nullable = false)
    private List<String> aliases = new ArrayList<>();

}
