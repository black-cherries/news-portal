package org.newsportal.identity;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code newsportal.privacy}.
 *
 * @param deletionRecoveryPeriod how long a deleted account can be restored (FR-052; default 7 days)
 * @param purgeEnabled run the purge job automatically
 * @param deletionLedgerFile file outside the database listing purged user ids (FR-055)
 */
@ConfigurationProperties("newsportal.privacy")
public record PrivacyProperties(Duration deletionRecoveryPeriod, boolean purgeEnabled, String deletionLedgerFile) {

	public PrivacyProperties {
		if (deletionRecoveryPeriod == null) {
			deletionRecoveryPeriod = Duration.ofDays(7);
		}
		if (deletionLedgerFile == null || deletionLedgerFile.isBlank()) {
			throw new IllegalStateException("newsportal.privacy.deletion-ledger-file is required");
		}
	}

}
