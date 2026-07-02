package com.melown.catalog.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;

@Configuration
public class ClockConfig {

    @Bean
    public Instant now() {
        return Instant.now(Clock.systemUTC());
    }
}
