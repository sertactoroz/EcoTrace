package com.ecotrace.api.gamification.api;

import java.math.BigDecimal;
import java.util.UUID;

public interface PointsApi {

    PointsAward awardForCollection(
            UUID userId,
            UUID collectionId,
            UUID wastePointId,
            BigDecimal categoryMultiplier,
            String volumeKey);
}
