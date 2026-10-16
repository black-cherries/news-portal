package org.newsportal.ingestion;

import java.time.Clock;
import java.util.NoSuchElementException;

import org.newsportal.source.NewsSource;
import org.newsportal.source.NewsSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Feed connector configuration (FR-008, FR-009). Callers must be authorized source managers. */
@Service
@Transactional
public class SourceFeedService {

	static final int MIN_INTERVAL = 5;

	static final int MAX_INTERVAL = 1440;

	private final SourceFeedRepository feeds;

	private final NewsSourceRepository sources;

	private final Clock clock;

	public SourceFeedService(SourceFeedRepository feeds, NewsSourceRepository sources, Clock clock) {
		this.feeds = feeds;
		this.sources = sources;
		this.clock = clock;
	}

	public SourceFeed addFeed(Long sourceId, String feedUrl, int refreshIntervalMinutes) {
		if (feedUrl == null || UrlCanonicalizer.canonicalize(feedUrl).isEmpty()) {
			throw new IllegalArgumentException("feedUrl must be an absolute http(s) URL");
		}
		checkInterval(refreshIntervalMinutes);
		NewsSource source = sources.findById(sourceId)
			.orElseThrow(() -> new NoSuchElementException("news source " + sourceId + " not found"));
		return feeds.save(new SourceFeed(source, feedUrl.strip(), refreshIntervalMinutes, clock.instant()));
	}

	public SourceFeed update(Long feedId, Boolean enabled, Integer refreshIntervalMinutes) {
		SourceFeed feed = find(feedId);
		if (enabled != null) {
			feed.setEnabled(enabled);
		}
		if (refreshIntervalMinutes != null) {
			checkInterval(refreshIntervalMinutes);
			feed.changeRefreshInterval(refreshIntervalMinutes);
		}
		return feed;
	}

	public SourceFeed setEnabled(Long feedId, boolean enabled) {
		return update(feedId, enabled, null);
	}

	@Transactional(readOnly = true)
	public SourceFeed find(Long feedId) {
		return feeds.findById(feedId).orElseThrow(() -> new NoSuchElementException("feed " + feedId + " not found"));
	}

	private static void checkInterval(int minutes) {
		if (minutes < MIN_INTERVAL || minutes > MAX_INTERVAL) {
			throw new IllegalArgumentException(
					"refreshIntervalMinutes must be between " + MIN_INTERVAL + " and " + MAX_INTERVAL);
		}
	}

}
