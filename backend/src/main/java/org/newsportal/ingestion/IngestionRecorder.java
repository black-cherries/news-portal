package org.newsportal.ingestion;

import java.time.Instant;
import java.util.List;

import org.newsportal.ingestion.IngestionJob.ItemCounts;
import org.newsportal.ingestion.QuarantinedRecord.Quarantine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Saves the job record, its quarantined entries and the feed's new state together. */
@Service
public class IngestionRecorder {

	private final SourceFeedRepository feeds;

	private final IngestionJobRepository jobs;

	private final QuarantinedRecordRepository quarantine;

	public IngestionRecorder(SourceFeedRepository feeds, IngestionJobRepository jobs,
			QuarantinedRecordRepository quarantine) {
		this.feeds = feeds;
		this.jobs = jobs;
		this.quarantine = quarantine;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public IngestionJob record(Long feedId, Instant startedAt, Instant finishedAt, FeedOutcome outcome,
			Integer httpStatus, ItemCounts counts, String diagnostic, List<Quarantine> quarantined,
			Instant nextRunAt, String etag, String lastModified) {
		IngestionJob job = jobs.save(
				new IngestionJob(feedId, startedAt, finishedAt, outcome, httpStatus, counts, diagnostic));
		for (Quarantine item : quarantined) {
			quarantine.save(new QuarantinedRecord(job.getId(), item, finishedAt));
		}
		SourceFeed feed = feeds.findById(feedId).orElseThrow();
		feed.recordResult(outcome, startedAt, nextRunAt);
		if (outcome == FeedOutcome.SUCCESS || outcome == FeedOutcome.NO_NEW_ITEMS) {
			feed.rememberValidators(etag, lastModified);
		}
		return job;
	}

}
