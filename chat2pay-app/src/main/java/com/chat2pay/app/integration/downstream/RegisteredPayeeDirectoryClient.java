package com.chat2pay.app.integration.downstream;

import com.chat2pay.app.domain.Profile;
import java.util.List;

public interface RegisteredPayeeDirectoryClient {

    List<RegisteredPayee> listRegisteredPayees(Profile profile);
}
