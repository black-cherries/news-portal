package org.newsportal.ingestion;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs due feeds one at a time. A failure in one feed never stops the others (FR-014). */
@Component
@ConditionalOnBooleanProperty("newsportal.ingestion.scheduler-enabled")
public class IngestionScheduler {

	private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

	private final SourceFeedRepository feeds;

	private final IngestionService ingestion;

	private final Clock clock;

	public IngestionScheduler(SourceFeedRepository feeds, IngestionService ingestion, Clock clock) {
		this.feeds = feeds;
		this.ingestion = ingestion;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${newsportal.ingestion.poll-interval:PT1M}", initialDelayString = "PT30S")
	public void runDueFeeds() {
		for (Long feedId : feeds.findDueFeedIds(clock.instant())) {
			try {
				ingestion.runFeed(feedId);
			}
			catch (RuntimeException ex) {
				log.error("Feed {}: unexpected ingestion error", feedId, ex);
			}
		}
	}

}
