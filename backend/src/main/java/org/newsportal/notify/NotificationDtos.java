package org.newsportal.notify;

import java.time.Instant;
import java.util.List;

/** JSON shapes for digests and breaking-news alerts. */
public final class NotificationDtos {

	private NotificationDtos() {
	}

	public enum Frequency {
		OFF, DAILY, WEEKLY
	}

	/**
	 * FR-066: everything is off by default. Empty {@code languages} or
	 * {@code topics} means "same as my For You settings". The digest language
	 * is part of the general preferences (FR-045). {@code nextDigestAt} is
	 * read-only.
	 */
	public record Settings(Frequency digest, Boolean breakingAlerts, List<String> languages, List<String> topics,
			Instant nextDigestAt) {
	}

	public enum UnsubscribeKind {
		DIGEST, ALERTS, ALL
	}

	public record UnsubscribeRequest(String token, UnsubscribeKind kind) {
	}

	public record AlertRequest(Long articleId, String reason) {
	}

	public record CancelRequest(String reason) {
	}

	public enum AlertStatus {
		/** Waiting for the hold to end; can still be cancelled. */
		QUEUED,
		/** Being sent in batches. */
		SENDING,
		SENT,
		CANCELLED
	}

	/**
	 * {@code skippedCount}: matching readers who already had the maximum
	 * number of alerts in the last 24 hours (FR-067).
	 */
	public record AlertView(long id, Long articleId, String articleTitle, String articleLanguage, String sourceName,
			AlertStatus status, String reason, String createdBy, Instant createdAt, Instant sendAfter, Instant sentAt,
			int recipientCount, int skippedCount, String cancelledBy, Instant cancelledAt, String cancelReason) {
	}

	public record AlertPage(List<AlertView> items, int page, int size, boolean hasMore) {
	}

}
