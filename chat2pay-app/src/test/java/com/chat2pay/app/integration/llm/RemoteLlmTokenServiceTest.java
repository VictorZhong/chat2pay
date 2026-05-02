package com.chat2pay.app.integration.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.config.Chat2PayProperties.RemoteAuth;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RemoteLlmTokenServiceTest {

    private static final String TOKEN_URL = "https://example.test/ib2b/token";

    private Chat2PayProperties properties;
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        properties = new Chat2PayProperties();
        RemoteAuth auth = properties.getLlm().getRemote().getAuth();
        auth.setTokenUrl(TOKEN_URL);
        auth.setUsername("test-acct");
        auth.setPassword("acctpwd");
        auth.setTokenTtlSeconds(60);

        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(Instant.parse("2026-05-02T00:00:00Z"));
    }

    @Test
    void returnsIssuedTokenAndCachesIt() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"issued_token\":\"jwt-abc\"}",
                        MediaType.APPLICATION_JSON));

        RemoteLlmTokenService service = new RemoteLlmTokenService(properties, builder, clock);

        String first = service.issueToken();
        String second = service.issueToken();

        assertThat(first).isEqualTo("jwt-abc");
        assertThat(second).isEqualTo("jwt-abc");
        server.verify();
    }

    @Test
    void refreshesTokenAfterTtlElapses() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"issued_token\":\"jwt-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"issued_token\":\"jwt-2\"}", MediaType.APPLICATION_JSON));

        RemoteLlmTokenService service = new RemoteLlmTokenService(properties, builder, clock);

        assertThat(service.issueToken()).isEqualTo("jwt-1");
        clock.advance(java.time.Duration.ofSeconds(120));
        assertThat(service.issueToken()).isEqualTo("jwt-2");
        server.verify();
    }

    @Test
    void wrapsHttpFailureInLlmUnavailableException() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withServerError());

        RemoteLlmTokenService service = new RemoteLlmTokenService(properties, builder, clock);

        assertThatThrownBy(service::issueToken)
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("trust token");
    }

    @Test
    void rejectsMissingTokenUrl() {
        properties.getLlm().getRemote().getAuth().setTokenUrl("");
        RemoteLlmTokenService service = new RemoteLlmTokenService(properties, builder, clock);
        assertThatThrownBy(service::issueToken).isInstanceOf(LlmUnavailableException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
