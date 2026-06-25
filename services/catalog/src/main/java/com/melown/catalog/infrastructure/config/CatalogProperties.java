package com.melown.catalog.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "catalog.policy")
public record CatalogProperties(Duration draftTTL) {
}

