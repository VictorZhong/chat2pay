package com.chat2pay.app.integration.downstream.auth;

import com.chat2pay.app.domain.Profile;

public interface DownstreamTokenService {

    String obtainSamlToken(Profile profile);
}
