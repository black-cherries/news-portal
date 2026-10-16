package org.newsportal.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Timers for alerts, digests and delivery-log cleanup. Off in tests, which call the sender directly. */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationJobs {

	@Bean
	@ConditionalOnBooleanProperty("newsportal.notifications.jobs-enabled")
	Schedule notificationSchedule(NotificationSender sender) {
		return new Schedule(sender);
	}

	static class Schedule {

		private static final Logger log = LoggerFactory.getLogger(Schedule.class);

		private final NotificationSender sender;

		Schedule(NotificationSender sender) {
			this.sender = sender;
		}

		@Scheduled(fixedDelayString = "${newsportal.notifications.check-interval}", initialDelayString = "PT1M")
		void run() {
			// Alerts first: they are time-sensitive.
			try {
				sender.sendDueAlerts();
			}
			catch (RuntimeException ex) {
				log.error("Alert run failed", ex);
			}
			try {
				sender.sendDueDigests();
			}
			catch (RuntimeException ex) {
				log.error("Digest run failed", ex);
			}
		}

		@Scheduled(cron = "0 15 4 * * *")
		void purge() {
			sender.purgeOldDeliveries();
		}

	}

}
