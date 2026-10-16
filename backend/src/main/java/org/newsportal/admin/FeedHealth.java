package org.newsportal.admin;

import java.time.Duration;
import java.time.Instant;

import org.newsportal.ingestion.FeedOutcome;

/**
 * Feed health for the admin dashboard (FR-079). Rights restrictions are shown
 * separately from ingestion failures.
 */
public enum FeedHealth {

	/** Turned off by a source manager. */
	DISABLED,
	/** Not fetched because the publisher's rights do not allow it. Not an ingestion failure. */
	RIGHTS_RESTRICTED,
	/** Never attempted yet. */
	PENDING,
	/** Three or more failures in a row, or failing without ever having succeeded. */
	FAILING,
	/** No successful fetch for longer than three refresh intervals (at least two hours). */
	STALE,
	/** Working again after a failure in the last 24 hours. */
	RECOVERING,
	HEALTHY;

	static final int FAILING_AFTER = 3;

	static final Duration MIN_STALE_AFTER = Duration.ofHours(2);

	static final Duration RECOVERY_WINDOW = Duration.ofHours(24);

	public static FeedHealth classify(boolean enabled, FeedOutcome lastOutcome, Instant lastAttemptAt,
			Instant lastSuccessAt, int consecutiveFailures, Duration refreshInterval, boolean failedRecently,
			Instant now) {
		if (!enabled) {
			return DISABLED;
		}
		if (lastOutcome == FeedOutcome.BLOCKED_BY_RIGHTS) {
			return RIGHTS_RESTRICTED;
		}
		if (lastAttemptAt == null) {
			return PENDING;
		}
		if (consecutiveFailures >= FAILING_AFTER || (consecutiveFailures > 0 && lastSuccessAt == null)) {
			return FAILING;
		}
		Duration staleAfter = refreshInterval.multipliedBy(3);
		if (staleAfter.compareTo(MIN_STALE_AFTER) < 0) {
			staleAfter = MIN_STALE_AFTER;
		}
		if (lastSuccessAt == null || lastSuccessAt.plus(staleAfter).isBefore(now)) {
			return STALE;
		}
		if (consecutiveFailures == 0 && failedRecently) {
			return RECOVERING;
		}
		return HEALTHY;
	}

}
