package com.ecotrace.api.config.properties;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(List<String> moderatorEmails, List<String> adminEmails) {

    public AuthProperties {
        moderatorEmails = moderatorEmails == null ? List.of() : moderatorEmails;
        adminEmails = adminEmails == null ? List.of() : adminEmails;
    }
}
