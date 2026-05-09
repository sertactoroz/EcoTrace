package com.ecotrace.api.config.properties;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(List<String> moderatorEmails) {

    public AuthProperties {
        moderatorEmails = moderatorEmails == null ? List.of() : moderatorEmails;
    }
}
