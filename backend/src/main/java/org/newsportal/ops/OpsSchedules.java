package org.newsportal.ops;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Timed backups, restore tests and monitoring. Each can be switched off separately. */
@Configuration
@EnableConfigurationProperties(OpsProperties.class)
public class OpsSchedules {

	@Bean
	@ConditionalOnBooleanProperty("newsportal.ops.backup.schedule-enabled")
	BackupSchedule backupSchedule(BackupService backups) {
		return new BackupSchedule(backups);
	}

	@Bean
	@ConditionalOnBooleanProperty("newsportal.ops.restore-test.schedule-enabled")
	RestoreTestSchedule restoreTestSchedule(BackupService backups) {
		return new RestoreTestSchedule(backups);
	}

	@Bean
	@ConditionalOnBooleanProperty("newsportal.ops.monitor.enabled")
	MonitorSchedule monitorSchedule(OpsMonitor monitor) {
		return new MonitorSchedule(monitor);
	}

	static class BackupSchedule {

		private final BackupService backups;

		BackupSchedule(BackupService backups) {
			this.backups = backups;
		}

		@Scheduled(cron = "${newsportal.ops.backup.cron}")
		void run() {
			backups.backup();
		}

	}

	static class RestoreTestSchedule {

		private final BackupService backups;

		RestoreTestSchedule(BackupService backups) {
			this.backups = backups;
		}

		@Scheduled(cron = "${newsportal.ops.restore-test.cron}")
		void run() {
			backups.restoreTest();
		}

	}

	static class MonitorSchedule {

		private final OpsMonitor monitor;

		MonitorSchedule(OpsMonitor monitor) {
			this.monitor = monitor;
		}

		@Scheduled(fixedDelayString = "${newsportal.ops.monitor.interval}", initialDelayString = "PT1M")
		void run() {
			monitor.check();
		}

	}

}
