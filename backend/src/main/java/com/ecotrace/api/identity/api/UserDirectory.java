package com.ecotrace.api.identity.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface UserDirectory {

    Map<UUID, UserSummary> getSummaries(Collection<UUID> userIds);
}
