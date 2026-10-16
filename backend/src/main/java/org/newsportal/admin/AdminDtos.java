package org.newsportal.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.newsportal.ingestion.FeedOutcome;
import org.newsportal.ingestion.QuarantinedRecord;
import org.newsportal.source.ContentUse;
import org.newsportal.source.ReviewAction;
import org.newsportal.source.RightsStatus;
import org.newsportal.source.SourceStatus;

/** JSON shapes for the staff API. */
public final class AdminDtos {

	private AdminDtos() {
	}

	// --- responses ---

	/**
	 * Current rights for one use. {@code status} is null when no review exists
	 * yet; {@code allowed}/{@code reason} give the effective result right now.
	 */
	public record RightsView(ContentUse use, RightsStatus status, Instant validFrom, Instant expiresAt,
			String conditions, boolean suspended, String suspendedReason, boolean allowed, String reason) {
	}

	public record FeedView(long id, String feedUrl, int refreshIntervalMinutes, boolean enabled, FeedHealth health,
			FeedOutcome lastOutcome, Instant lastAttemptAt, Instant lastSuccessAt, int consecutiveFailures,
			Instant nextRunAt) {
	}

	public record SourceView(long id, String code, String name, String homepageUrl, String primaryLanguage,
			SourceStatus status, String statusReason, String termsUrl, String termsSummary, List<RightsView> rights,
			List<FeedView> feeds) {
	}

	public record ReviewView(long id, ContentUse use, ReviewAction action, String previousStatus, String newStatus,
			Instant validFrom, Instant expiresAt, String reviewer, String notes, String evidenceUrl,
			LocalDate nextReviewDue, Instant reviewedAt) {
	}

	public record SourceDetail(SourceView source, List<ReviewView> reviews) {
	}

	public record JobView(long id, Instant startedAt, Instant finishedAt, FeedOutcome outcome, Integer httpStatus,
			int itemsSeen, int itemsCreated, int itemsUpdated, int itemsUnchanged, int itemsQuarantined,
			String diagnostic) {
	}

	public record QuarantineView(QuarantinedRecord.Reason reason, String entryGuid, String entryLink,
			String diagnostic) {
	}

	/** One row of the source-health dashboard. */
	public record FeedHealthRow(long feedId, String feedUrl, long sourceId, String sourceCode, String sourceName,
			SourceStatus sourceStatus, FeedHealth health, FeedOutcome lastOutcome, Instant lastAttemptAt,
			Instant lastSuccessAt, int consecutiveFailures, Instant nextRunAt) {
	}

	// --- requests ---

	public record CreateSourceRequest(String code, String name, String homepageUrl, String primaryLanguage,
			String termsUrl, String termsSummary) {
	}

	public record UpdateTermsRequest(String termsUrl, String termsSummary, String notes) {
	}

	public record NotesRequest(String notes) {
	}

	public record RightsRequest(RightsStatus status, Instant validFrom, Instant expiresAt, String conditions,
			String notes, String evidenceUrl, LocalDate nextReviewDue) {
	}

	public record CreateFeedRequest(String feedUrl, Integer refreshIntervalMinutes) {
	}

	public record UpdateFeedRequest(Boolean enabled, Integer refreshIntervalMinutes) {
	}

}
