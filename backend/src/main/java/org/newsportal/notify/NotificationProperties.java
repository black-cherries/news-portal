package org.newsportal.notify;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code newsportal.notifications}.
 *
 * @param jobsEnabled run the digest and alert senders on a timer
 * @param checkInterval how often the senders look for due work
 * @param alertHold delay before a breaking-news alert is sent, so it can be cancelled
 * @param maxAlertsPerReaderPerDay alerts one reader can receive in 24 hours (FR-067)
 * @param maxAlertsPerDay alerts staff can create in 24 hours across the whole app (FR-067)
 * @param batchSize recipients handled per alert or digest run
 * @param deliveryRetention how long the per-reader delivery log is kept
 */
@ConfigurationProperties("newsportal.notifications")
public record NotificationProperties(boolean jobsEnabled, Duration checkInterval, Duration alertHold,
		int maxAlertsPerReaderPerDay, int maxAlertsPerDay, int batchSize, Duration deliveryRetention) {
}
