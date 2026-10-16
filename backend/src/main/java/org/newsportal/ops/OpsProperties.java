package org.newsportal.ops;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code newsportal.ops}.
 *
 * @param pgBinDir folder with pg_dump/pg_restore; empty means they are on PATH
 */
@ConfigurationProperties("newsportal.ops")
public record OpsProperties(String pgBinDir, Backup backup, RestoreTest restoreTest, Monitor monitor) {

	/**
	 * @param scheduleEnabled run the daily backup automatically
	 * @param dir where dumps are written (put it on a different disk from the database for the pilot)
	 * @param retentionDays dumps older than this are removed; the newest good dump is always kept
	 * @param maxAge alert if no successful backup is newer than this
	 */
	public record Backup(boolean scheduleEnabled, String dir, String cron, int retentionDays, Duration maxAge) {
	}

	/**
	 * @param dbUrl scratch database the latest dump is restored into; must not be the live database
	 * @param maxAge alert if no successful restore test is newer than this
	 */
	public record RestoreTest(boolean scheduleEnabled, String dbUrl, String cron, Duration maxAge) {
	}

	/**
	 * @param alertRecipients email addresses; when empty, all active system administrators
	 */
	public record Monitor(boolean enabled, Duration interval, Duration alertCooldown, long minFreeDiskMb,
			List<String> alertRecipients) {
	}

	public OpsProperties {
		if (pgBinDir == null) {
			pgBinDir = "";
		}
		if (backup == null || backup.dir() == null || backup.dir().isBlank()) {
			throw new IllegalStateException("newsportal.ops.backup.dir is required");
		}
		if (backup.retentionDays() < 1) {
			throw new IllegalStateException("newsportal.ops.backup.retention-days must be at least 1");
		}
		if (monitor == null) {
			throw new IllegalStateException("newsportal.ops.monitor settings are required");
		}
	}

}
