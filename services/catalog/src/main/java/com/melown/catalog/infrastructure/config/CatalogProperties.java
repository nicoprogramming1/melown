package com.melown.catalog.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "catalog.policy")
public record CatalogProperties(Duration draftTtl) {}
