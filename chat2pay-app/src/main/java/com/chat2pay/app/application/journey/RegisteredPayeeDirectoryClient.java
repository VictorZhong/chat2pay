package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.Profile;
import com.chat2pay.app.domain.RegisteredPayee;
import java.util.List;

public interface RegisteredPayeeDirectoryClient {

    List<RegisteredPayee> listRegisteredPayees(Profile profile);
}
