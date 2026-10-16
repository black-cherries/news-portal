package org.newsportal.ingestion;

import java.time.Duration;
import java.time.Instant;

import org.newsportal.source.NewsSource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** One configured connector for a publisher, plus its current fetch state. */
@Entity
@Table(name = "source_feed")
public class SourceFeed {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "source_id", nullable = false)
	private NewsSource source;

	@Column(name = "feed_url", nullable = false, length = 1000)
	private String feedUrl;

	@Column(nullable = false, length = 20)
	private String connector = "RSS";

	@Column(name = "refresh_interval_minutes", nullable = false)
	private int refreshIntervalMinutes;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(name = "next_run_at", nullable = false)
	private Instant nextRunAt;

	@Column(name = "last_attempt_at")
	private Instant lastAttemptAt;

	@Column(name = "last_success_at")
	private Instant lastSuccessAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "last_outcome", length = 30)
	private FeedOutcome lastOutcome;

	@Column(name = "consecutive_failures", nullable = false)
	private int consecutiveFailures;

	@Column(length = 500)
	private String etag;

	@Column(name = "last_modified", length = 100)
	private String lastModified;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Version
	private long version;

	protected SourceFeed() {
	}

	public SourceFeed(NewsSource source, String feedUrl, int refreshIntervalMinutes, Instant firstRunAt) {
		this.source = source;
		this.feedUrl = feedUrl;
		this.refreshIntervalMinutes = refreshIntervalMinutes;
		this.nextRunAt = firstRunAt;
	}

	void recordResult(FeedOutcome outcome, Instant attemptedAt, Instant nextRunAt) {
		this.lastOutcome = outcome;
		this.lastAttemptAt = attemptedAt;
		this.nextRunAt = nextRunAt;
		if (outcome.isFailure()) {
			consecutiveFailures++;
		}
		else if (outcome != FeedOutcome.BLOCKED_BY_RIGHTS) {
			consecutiveFailures = 0;
			lastSuccessAt = attemptedAt;
		}
	}

	void rememberValidators(String etag, String lastModified) {
		this.etag = etag;
		this.lastModified = lastModified;
	}

	void changeRefreshInterval(int minutes) {
		this.refreshIntervalMinutes = minutes;
	}

	public int getRefreshIntervalMinutes() {
		return refreshIntervalMinutes;
	}

	public Duration refreshInterval() {
		return Duration.ofMinutes(refreshIntervalMinutes);
	}

	public Long getId() {
		return id;
	}

	public NewsSource getSource() {
		return source;
	}

	public String getFeedUrl() {
		return feedUrl;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Instant getNextRunAt() {
		return nextRunAt;
	}

	public Instant getLastAttemptAt() {
		return lastAttemptAt;
	}

	public Instant getLastSuccessAt() {
		return lastSuccessAt;
	}

	public FeedOutcome getLastOutcome() {
		return lastOutcome;
	}

	public int getConsecutiveFailures() {
		return consecutiveFailures;
	}

	public String getEtag() {
		return etag;
	}

	public String getLastModified() {
		return lastModified;
	}

}
