package com.melown.catalog.infrastructure.config;

import com.melown.catalog.domain.policy.CatalogPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
public class CatalogConfig {

    @Bean
    public CatalogPolicy catalogPolicy(CatalogProperties props) {
        return new CatalogPolicy(props.draftTtl());
    }
}
