package com.chat2pay.app.common;

import com.github.f4b6a3.ulid.UlidCreator;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class UlidFactory {

    public String nextUlid() {
        return UlidCreator.getUlid().toString();
    }

    public String nextPrefixed(String prefix) {
        return prefix + "_" + nextUlid().toLowerCase(Locale.ROOT);
    }
}
