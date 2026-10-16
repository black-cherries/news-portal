package org.newsportal.config;

import org.newsportal.identity.PrivacyProperties;
import org.newsportal.ingestion.IngestionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({ IngestionProperties.class, PrivacyProperties.class })
public class SchedulingConfig {
}
