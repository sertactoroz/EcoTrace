package com.ecotrace.api.config.properties;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gamification")
public record GamificationProperties(Points points, Map<String, BigDecimal> volumeMultipliers) {

    public record Points(int baseCollection, int successfulReportBonus, int falseReportPenalty) {}
}
