package com.ecotrace.api.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth2")
public record OAuth2Properties(Google google) {

    public record Google(
            String clientId,
            String issuer,
            String jwksUri,
            Duration jwksCacheTtl) {}
}
