package com.ecotrace.api.collection.domain;

import com.ecotrace.api.collection.entity.Collection;
import com.ecotrace.api.collection.entity.CollectionEvidence;
import com.ecotrace.api.collection.entity.PhotoKind;
import com.ecotrace.api.config.properties.FraudProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public final class FraudGate {

    public enum Reason {
        DISTANCE_TOO_FAR,
        DWELL_TOO_SHORT,
        MISSING_AFTER_PHOTO,
        VELOCITY_EXCEEDED
    }

    public record Failure(Reason reason, String detail) {}

    private final FraudProperties config;

    public FraudGate(FraudProperties config) {
        this.config = config;
    }

    public Optional<Failure> evaluate(Collection collection,
                                      List<CollectionEvidence> evidence,
                                      long collectionsInLastHour) {
        BigDecimal distance = collection.getDistanceFromPinM();
        if (distance != null && distance.compareTo(BigDecimal.valueOf(config.maxDistanceM())) > 0) {
            return Optional.of(new Failure(
                    Reason.DISTANCE_TOO_FAR,
                    "%sm > max %dm".formatted(distance.toPlainString(), config.maxDistanceM())));
        }

        Integer dwell = collection.getDwellSeconds();
        if (dwell != null && dwell < config.minDwellSeconds()) {
            return Optional.of(new Failure(
                    Reason.DWELL_TOO_SHORT,
                    "%ds < min %ds".formatted(dwell, config.minDwellSeconds())));
        }

        if (config.requireAfterPhoto()
                && evidence.stream().noneMatch(e -> e.getKind() == PhotoKind.AFTER)) {
            return Optional.of(new Failure(
                    Reason.MISSING_AFTER_PHOTO,
                    "no AFTER photo on collection"));
        }

        if (collectionsInLastHour > config.maxCollectionsPerHour()) {
            return Optional.of(new Failure(
                    Reason.VELOCITY_EXCEEDED,
                    "%d collections in last hour > max %d"
                            .formatted(collectionsInLastHour, config.maxCollectionsPerHour())));
        }

        return Optional.empty();
    }
}
