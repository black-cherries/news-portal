package org.newsportal.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the purge hourly and re-applies recorded deletions at startup. */
@Configuration
@ConditionalOnBooleanProperty("newsportal.privacy.purge-enabled")
public class PrivacyJobs implements ApplicationRunner {

	private final PrivacyService privacy;

	public PrivacyJobs(PrivacyService privacy) {
		this.privacy = privacy;
	}

	@Override
	public void run(ApplicationArguments args) {
		privacy.reapplyLedger();
	}

	@Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
	public void purgeDue() {
		privacy.purgeDue();
	}

}
