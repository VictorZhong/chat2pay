package com.chat2pay.app.integration.downstream;

import com.chat2pay.app.config.Chat2PayProperties;
import com.chat2pay.app.domain.Profile;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "chat2pay.downstream", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockRegisteredPayeeDirectoryClient implements RegisteredPayeeDirectoryClient {

    private final Chat2PayProperties properties;

    public MockRegisteredPayeeDirectoryClient(Chat2PayProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<RegisteredPayee> listRegisteredPayees(Profile profile) {
        return properties.getDownstream().getPayee().getMockPayees().stream()
                .map(payee -> new RegisteredPayee(
                        payee.getPayeeIdIndex(),
                        payee.getPayeeType(),
                        payee.getName(),
                        payee.getDescription()))
                .toList();
    }
}
