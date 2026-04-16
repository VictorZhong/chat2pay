package com.chat2pay.app.integration.downstream;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class DownstreamHeadersFactory {

    public HttpHeaders buildHeaders(String samlToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Accept", "*/*");
        headers.add("Accept-Language", "en-HK");
        headers.add("Content-Type", "application/json; charset=UTF-8");
        headers.add("X-abcd-Channel-Id", "WEB");
        headers.add("X-abcd-Chnl-CountryCode", "HK");
        headers.add("X-abcd-Chnl-Group-Member", "tktk");
        headers.add("X-abcd-Locale", "en_HK");
        headers.add("X-abcd-saml3", samlToken);
        headers.add("X-abcd-Request-Correlation-Id", UUID.randomUUID().toString());
        headers.add("X-abcd-Session-Correlation-Id", UUID.randomUUID().toString());
        headers.add("X-abcd-Source-System-Id", "11114418_O88");
        headers.add("X-abcd-Src-Device-Id", "192.168.1.1, 192.168.1.2");
        headers.add("X-abcd-Src-UserAgent",
                "Mozilla/5.0 (iPad; U; CPU OS 3_2_1 like Mac OS X; en-us) AppleWebKit/531.21.10 (KHTML, like Gecko) Mobile/7B405");
        return headers;
    }
}
