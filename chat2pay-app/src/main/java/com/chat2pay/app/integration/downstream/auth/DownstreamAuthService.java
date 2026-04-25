package com.chat2pay.app.integration.downstream.auth;

import org.springframework.http.HttpHeaders;

/**
 * Encapsulates the shared login-to-SAML flow used before downstream business calls.
 */
public interface DownstreamAuthService {

    String login(String profileId);

    HttpHeaders authenticatedHeaders(String profileId, String samlToken);
}
