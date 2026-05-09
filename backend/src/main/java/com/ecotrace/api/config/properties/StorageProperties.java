package com.ecotrace.api.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
        String bucket,
        String region,
        String endpoint,
        String accessKey,
        String secretKey,
        Duration presignTtl,
        long maxUploadBytes) {}
