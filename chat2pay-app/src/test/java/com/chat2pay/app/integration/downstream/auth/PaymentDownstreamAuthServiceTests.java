package com.chat2pay.app.integration.downstream.auth;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.persistence.repository.ProfileStore;
import com.chat2pay.app.persistence.repository.ProfileStore.RuntimeProfile;
import com.chat2pay.app.persistence.repository.ProfileStore.SourceSystemContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentDownstreamAuthServiceTests {

    private static final String SOURCE_SYSTEM_HEADER = "X-zzzz-Source-System-Id";

    private final ProfileStore profiles = mock(ProfileStore.class);

    @Test
    void authenticatedHeadersUseContextSpecificProfileSourceSystemId() {
        when(profiles.runtimeProfile("profile_1")).thenReturn(profile(
                "PERM_SHOULD_NOT_BE_USED",
                "COMMON_SRC",
                "PAYEE_SRC",
                "DOMESTIC_SRC",
                "CB_SRC"
        ));
        PaymentDownstreamAuthService service = new PaymentDownstreamAuthService(properties("ENV_SRC"), profiles);

        HttpHeaders payeeHeaders = service.authenticatedHeaders(
                "profile_1", "saml", SourceSystemContext.PAYEE_LOOKUP);
        HttpHeaders domesticHeaders = service.authenticatedHeaders(
                "profile_1", "saml", SourceSystemContext.DOMESTIC_PAYMENT_CONFIRM);
        HttpHeaders crossBorderHeaders = service.authenticatedHeaders(
                "profile_1", "saml", SourceSystemContext.CROSS_BORDER_PAYMENT);
        HttpHeaders defaultHeaders = service.authenticatedHeaders(
                "profile_1", "saml", SourceSystemContext.DEFAULT);

        assertThat(payeeHeaders.getFirst(SOURCE_SYSTEM_HEADER)).isEqualTo("PAYEE_SRC");
        assertThat(domesticHeaders.getFirst(SOURCE_SYSTEM_HEADER)).isEqualTo("DOMESTIC_SRC");
        assertThat(crossBorderHeaders.getFirst(SOURCE_SYSTEM_HEADER)).isEqualTo("CB_SRC");
        assertThat(defaultHeaders.getFirst(SOURCE_SYSTEM_HEADER)).isEqualTo("COMMON_SRC");
    }

    @Test
    void authenticatedHeadersFallbackToConfiguredDefaultWithoutUsingPermNetId() {
        when(profiles.runtimeProfile("profile_1")).thenReturn(profile(
                "PERM_SHOULD_NOT_BE_USED",
                null,
                null,
                null,
                null
        ));
        PaymentDownstreamAuthService service = new PaymentDownstreamAuthService(properties("ENV_SRC"), profiles);

        HttpHeaders headers = service.authenticatedHeaders(
                "profile_1", "saml", SourceSystemContext.DOMESTIC_PAYMENT_CONFIRM);

        assertThat(headers.getFirst(SOURCE_SYSTEM_HEADER)).isEqualTo("ENV_SRC");
    }

    private RuntimeProfile profile(String permNetId,
                                   String sourceSystemId,
                                   String payeeSourceSystemId,
                                   String domesticPaymentSourceSystemId,
                                   String crossBorderPaymentSourceSystemId) {
        return new RuntimeProfile(
                "profile_1",
                "guid_1",
                permNetId,
                "username_1",
                "password_1",
                "123456789",
                "CUR",
                "HKD",
                sourceSystemId,
                payeeSourceSystemId,
                domesticPaymentSourceSystemId,
                crossBorderPaymentSourceSystemId
        );
    }

    private Chat2PayProperties properties(String sourceSystemId) {
        return new Chat2PayProperties(
                "COPILOT_PERSONAL",
                "REMOTE_API",
                "HKD",
                new Chat2PayProperties.IntentProperties(true, 350, 0.0),
                new Chat2PayProperties.DownstreamProperties(
                        false,
                        "https://example.test/login/{username}",
                        "https://example.test/accounts",
                        "https://example.test/payees",
                        "https://example.test/confirm",
                        30000,
                        60,
                        "WEB",
                        "HK",
                        "HBAP",
                        "en_HK",
                        sourceSystemId,
                        "device_1",
                        "agent_1"
                ),
                null,
                null
        );
    }
}
