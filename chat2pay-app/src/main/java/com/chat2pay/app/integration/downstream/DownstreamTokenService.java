package com.chat2pay.app.integration.downstream;

import com.chat2pay.app.domain.Profile;

public interface DownstreamTokenService {

    String obtainSamlToken(Profile profile);
}
