package com.ecotrace.api.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fraud")
public record FraudProperties(
        int maxDistanceM,
        int minDwellSeconds,
        int maxCollectionsPerHour,
        boolean requireAfterPhoto) {

    public FraudProperties {
        if (maxDistanceM <= 0) maxDistanceM = 50;
        if (minDwellSeconds < 0) minDwellSeconds = 30;
        if (maxCollectionsPerHour <= 0) maxCollectionsPerHour = 10;
    }
}
