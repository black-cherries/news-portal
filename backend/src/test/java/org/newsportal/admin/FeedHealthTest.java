package org.newsportal.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.newsportal.ingestion.FeedOutcome;

class FeedHealthTest {

	private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	private static final Duration INTERVAL = Duration.ofMinutes(30);

	@Test
	void disabledAndRightsRestrictedAreReportedFirst() {
		assertThat(classify(false, FeedOutcome.HTTP_ERROR, ago(1), null, 5, false)).isEqualTo(FeedHealth.DISABLED);
		assertThat(classify(true, FeedOutcome.BLOCKED_BY_RIGHTS, ago(1), ago(600), 0, false))
			.isEqualTo(FeedHealth.RIGHTS_RESTRICTED);
	}

	@Test
	void neverAttemptedIsPending() {
		assertThat(classify(true, null, null, null, 0, false)).isEqualTo(FeedHealth.PENDING);
	}

	@Test
	void threeFailuresInARowOrNeverSucceededIsFailing() {
		assertThat(classify(true, FeedOutcome.HTTP_ERROR, ago(1), ago(20), 3, true)).isEqualTo(FeedHealth.FAILING);
		assertThat(classify(true, FeedOutcome.NOT_FOUND, ago(1), null, 1, true)).isEqualTo(FeedHealth.FAILING);
	}

	@Test
	void noSuccessForThreeIntervalsOrTwoHoursIsStale() {
		// 3 x 30 min = 90 min, but the minimum is 2 hours.
		assertThat(classify(true, FeedOutcome.NETWORK_ERROR, ago(1), ago(100), 1, true)).isEqualTo(FeedHealth.HEALTHY);
		assertThat(classify(true, FeedOutcome.NETWORK_ERROR, ago(1), ago(121), 2, true)).isEqualTo(FeedHealth.STALE);
	}

	@Test
	void successAfterRecentFailureIsRecovering() {
		assertThat(classify(true, FeedOutcome.SUCCESS, ago(1), ago(1), 0, true)).isEqualTo(FeedHealth.RECOVERING);
		assertThat(classify(true, FeedOutcome.NO_NEW_ITEMS, ago(1), ago(1), 0, false)).isEqualTo(FeedHealth.HEALTHY);
	}

	@Test
	void emptyFeedOrNotModifiedCountAsHealthy() {
		assertThat(classify(true, FeedOutcome.NO_NEW_ITEMS, ago(5), ago(5), 0, false)).isEqualTo(FeedHealth.HEALTHY);
		assertThat(classify(true, FeedOutcome.NOT_MODIFIED, ago(5), ago(5), 0, false)).isEqualTo(FeedHealth.HEALTHY);
	}

	private static FeedHealth classify(boolean enabled, FeedOutcome last, Instant attempt, Instant success,
			int failures, boolean failedRecently) {
		return FeedHealth.classify(enabled, last, attempt, success, failures, INTERVAL, failedRecently, NOW);
	}

	private static Instant ago(int minutes) {
		return NOW.minus(Duration.ofMinutes(minutes));
	}

}
