package org.newsportal.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.newsportal.ingestion.ArticleUpsertService.Result;
import org.newsportal.ingestion.FeedFetcher.FetchResult;
import org.newsportal.ingestion.FeedParser.FeedEntry;
import org.newsportal.ingestion.FeedParser.MalformedFeedException;
import org.newsportal.ingestion.FeedParser.ParsedFeed;
import org.newsportal.ingestion.IngestionJob.ItemCounts;
import org.newsportal.ingestion.QuarantinedRecord.Quarantine;
import org.newsportal.ingestion.QuarantinedRecord.Reason;
import org.newsportal.source.ContentUse;
import org.newsportal.source.NewsSource;
import org.newsportal.source.RightsDecision;
import org.newsportal.source.SourceRightsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one feed: rights check, fetch, parse, store each valid entry,
 * quarantine invalid ones, then record the job and schedule the next run.
 */
@Service
public class IngestionService {

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	static final int MAX_TITLE = 1000;

	static final int MAX_URL = 2000;

	static final Duration MAX_RETRY_AFTER = Duration.ofHours(24);

	private final SourceFeedRepository feeds;

	private final SourceRightsService rights;

	private final FeedFetcher fetcher;

	private final FeedParser parser;

	private final ArticleUpsertService upserts;

	private final IngestionRecorder recorder;

	private final IngestionProperties props;

	private final TransactionTemplate tx;

	private final Clock clock;

	public IngestionService(SourceFeedRepository feeds, SourceRightsService rights, FeedFetcher fetcher,
			FeedParser parser, ArticleUpsertService upserts, IngestionRecorder recorder, IngestionProperties props,
			TransactionTemplate transactionTemplate, Clock clock) {
		this.feeds = feeds;
		this.rights = rights;
		this.fetcher = fetcher;
		this.parser = parser;
		this.upserts = upserts;
		this.recorder = recorder;
		this.props = props;
		this.tx = transactionTemplate;
		this.clock = clock;
	}

	public IngestionJob runFeed(Long feedId) {
		Instant started = clock.instant();
		FeedSnapshot feed = tx.execute(status -> {
			SourceFeed f = feeds.findById(feedId).orElseThrow();
			NewsSource s = f.getSource();
			return new FeedSnapshot(f.getId(), s.getId(), s.getPrimaryLanguage(), f.getFeedUrl(), f.getEtag(),
					f.getLastModified(), f.refreshInterval(), f.getConsecutiveFailures());
		});

		// FR-005/FR-007: never contact a publisher whose metadata use is not currently permitted.
		RightsDecision metadata = rights.check(feed.sourceId(), ContentUse.METADATA);
		if (!metadata.allowed()) {
			return finish(feed, started, FeedOutcome.BLOCKED_BY_RIGHTS, null, ItemCounts.NONE,
					"METADATA use blocked: " + metadata.reason(), List.of(), null, null);
		}

		FetchResult fetched = fetcher.fetch(feed.url(), feed.etag(), feed.lastModified(), started);
		if (fetched.outcome() != null) {
			return finish(feed, started, fetched.outcome(), fetched.httpStatus(), ItemCounts.NONE,
					fetched.diagnostic(), List.of(), fetched.retryAfter(), null);
		}

		ParsedFeed parsed;
		try {
			parsed = parser.parse(fetched.body());
		}
		catch (MalformedFeedException ex) {
			return finish(feed, started, FeedOutcome.MALFORMED, fetched.httpStatus(), ItemCounts.NONE,
					ex.getMessage(), List.of(), null, null);
		}

		boolean storeContent = rights.check(feed.sourceId(), ContentUse.STORAGE).allowed();
		String language = (parsed.language() != null) ? parsed.language() : feed.defaultLanguage();
		List<FeedEntry> entries = parsed.entries();
		String diagnostic = null;
		if (entries.size() > props.maxItemsPerFeed()) {
			diagnostic = "Feed had " + entries.size() + " entries; processed first " + props.maxItemsPerFeed();
			entries = entries.subList(0, props.maxItemsPerFeed());
		}

		int created = 0, updated = 0, unchanged = 0;
		List<Quarantine> quarantined = new ArrayList<>();
		for (FeedEntry entry : entries) {
			Quarantine invalid = validate(entry);
			if (invalid != null) {
				quarantined.add(invalid);
				continue;
			}
			String canonical = UrlCanonicalizer.canonicalize(entry.link()).orElseThrow();
			try {
				Result result = upserts.upsert(feed.sourceId(), entry, canonical, language, storeContent, started);
				switch (result) {
					case CREATED -> created++;
					case UPDATED -> updated++;
					case UNCHANGED -> unchanged++;
				}
			}
			catch (RuntimeException ex) {
				log.warn("Feed {}: could not store entry {}", feed.id(), entry.link(), ex);
				quarantined.add(new Quarantine(Reason.PERSISTENCE_ERROR, entry.guid(), entry.link(),
						ex.getClass().getSimpleName()));
			}
		}

		ItemCounts counts = new ItemCounts(entries.size(), created, updated, unchanged, quarantined.size());
		FeedOutcome outcome;
		if (!entries.isEmpty() && quarantined.size() == entries.size()) {
			outcome = FeedOutcome.MALFORMED;
			diagnostic = "Every entry failed validation";
		}
		else {
			outcome = (created + updated > 0) ? FeedOutcome.SUCCESS : FeedOutcome.NO_NEW_ITEMS;
		}
		return finish(feed, started, outcome, fetched.httpStatus(), counts, diagnostic, quarantined, null, fetched);
	}

	private IngestionJob finish(FeedSnapshot feed, Instant started, FeedOutcome outcome, Integer httpStatus,
			ItemCounts counts, String diagnostic, List<Quarantine> quarantined, Duration retryAfter,
			FetchResult fetched) {
		Instant finished = clock.instant();
		Instant nextRun = started.plus(nextDelay(feed, outcome, retryAfter));
		String etag = (fetched == null) ? null : fetched.etag();
		String lastModified = (fetched == null) ? null : fetched.lastModified();
		IngestionJob job = recorder.record(feed.id(), started, finished, outcome, httpStatus, counts, diagnostic,
				quarantined, nextRun, etag, lastModified);
		if (outcome.isFailure()) {
			log.warn("Feed {} failed: {} {}", feed.id(), outcome, job.getDiagnostic());
		}
		return job;
	}

	/**
	 * FR-014: no immediate retries. After a failure the next attempt waits the
	 * refresh interval doubled per consecutive failure, capped at maxBackoff.
	 * A publisher's Retry-After is honoured up to 24 hours.
	 */
	Duration nextDelay(FeedSnapshot feed, FeedOutcome outcome, Duration retryAfter) {
		Duration interval = feed.interval();
		if (!outcome.isFailure()) {
			return interval;
		}
		int failures = Math.min(feed.consecutiveFailures() + 1, 20);
		Duration backoff = interval.multipliedBy(1L << (failures - 1));
		if (backoff.compareTo(props.maxBackoff()) > 0) {
			backoff = props.maxBackoff();
		}
		if (backoff.compareTo(interval) < 0) {
			backoff = interval;
		}
		if (retryAfter != null && retryAfter.compareTo(backoff) > 0) {
			backoff = retryAfter.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : retryAfter;
		}
		return backoff;
	}

	static Quarantine validate(FeedEntry entry) {
		if (entry.link() == null) {
			return new Quarantine(Reason.MISSING_LINK, entry.guid(), null, "Entry has no link");
		}
		if (entry.link().length() > MAX_URL) {
			return new Quarantine(Reason.FIELD_TOO_LONG, entry.guid(), entry.link(), "Link longer than " + MAX_URL);
		}
		if (UrlCanonicalizer.canonicalize(entry.link()).isEmpty()) {
			return new Quarantine(Reason.INVALID_LINK, entry.guid(), entry.link(), "Not an absolute http(s) URL");
		}
		if (entry.title() == null || entry.title().isBlank()) {
			return new Quarantine(Reason.MISSING_TITLE, entry.guid(), entry.link(), "Entry has no title");
		}
		if (entry.title().strip().length() > MAX_TITLE) {
			return new Quarantine(Reason.FIELD_TOO_LONG, entry.guid(), entry.link(), "Title longer than " + MAX_TITLE);
		}
		if (entry.guid() != null && entry.guid().length() > 1000) {
			return new Quarantine(Reason.FIELD_TOO_LONG, null, entry.link(), "GUID longer than 1000");
		}
		return null;
	}

	record FeedSnapshot(Long id, Long sourceId, String defaultLanguage, String url, String etag, String lastModified,
			Duration interval, int consecutiveFailures) {
	}

}
