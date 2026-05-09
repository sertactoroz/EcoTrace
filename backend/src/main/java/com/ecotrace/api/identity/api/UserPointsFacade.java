package com.ecotrace.api.identity.api;

import java.util.List;
import java.util.UUID;

public interface UserPointsFacade {

    long getTotalPoints(UUID userId);

    void setPointsAndLevel(UUID userId, long totalPoints, int level);

    List<UserPointsSnapshot> findAllWithPoints();
}
