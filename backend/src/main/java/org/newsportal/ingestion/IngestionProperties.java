package org.newsportal.ingestion;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code newsportal.ingestion}.
 *
 * @param schedulerEnabled run due feeds automatically
 * @param pollInterval how often to look for due feeds
 * @param userAgent identifies this app to publishers
 * @param connectTimeout TCP connect timeout
 * @param requestTimeout whole-request timeout
 * @param maxFeedBytes larger responses are rejected as TOO_LARGE
 * @param maxItemsPerFeed entries beyond this are ignored in one run
 * @param maxBackoff upper bound on the delay after repeated failures (FR-014)
 */
@ConfigurationProperties("newsportal.ingestion")
public record IngestionProperties(boolean schedulerEnabled, Duration pollInterval, String userAgent,
		Duration connectTimeout, Duration requestTimeout, int maxFeedBytes, int maxItemsPerFeed,
		Duration maxBackoff) {

	public IngestionProperties {
		if (pollInterval == null) {
			pollInterval = Duration.ofMinutes(1);
		}
		if (userAgent == null || userAgent.isBlank()) {
			userAgent = "NewsPortalBot/0.1 (+contact: site administrator)";
		}
		if (connectTimeout == null) {
			connectTimeout = Duration.ofSeconds(10);
		}
		if (requestTimeout == null) {
			requestTimeout = Duration.ofSeconds(30);
		}
		if (maxFeedBytes <= 0) {
			maxFeedBytes = 5 * 1024 * 1024;
		}
		if (maxItemsPerFeed <= 0) {
			maxItemsPerFeed = 200;
		}
		if (maxBackoff == null) {
			maxBackoff = Duration.ofHours(6);
		}
	}

}
