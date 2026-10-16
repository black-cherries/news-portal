package org.newsportal.source;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A reviewer's decision on one content use (FR-003). {@code validFrom},
 * {@code expiresAt}, {@code conditions}, {@code evidenceUrl} and
 * {@code nextReviewDue} are optional.
 */
public record RightsReview(RightsStatus status, Instant validFrom, Instant expiresAt, String conditions,
		String reviewer, String notes, String evidenceUrl, LocalDate nextReviewDue) {
}
