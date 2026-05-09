package com.ecotrace.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@ConfigurationPropertiesScan("com.ecotrace.api.config.properties")
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@EnableTransactionManagement
@EnableAsync
public class EcoTraceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcoTraceApplication.class, args);
    }
}
