package org.newsportal.report;

import java.time.Instant;
import java.util.List;

/** JSON shapes for reports. */
public final class ReportDtos {

	private ReportDtos() {
	}

	public enum Type {
		TECHNICAL, BROKEN_LINK, LANGUAGE_ERROR, TRANSLATION_ISSUE, MISSING_ATTRIBUTION, SUSPECTED_DUPLICATE,
		MISLEADING_WORDING, CORRECTION_REQUEST
	}

	/** FR-071. */
	public enum Status {
		RECEIVED, UNDER_REVIEW, RESOLVED
	}

	public enum Resolution {
		FIXED, PUBLISHER_NOTIFIED, NO_ACTION_NEEDED, DUPLICATE_REPORT, NOT_REPRODUCIBLE
	}

	public record SubmitRequest(Type type, Long articleId, String description, String pageUrl, Boolean notifyMe) {
	}

	public record Submitted(String reference, Status status) {
	}

	/** An update the reporter can see. Internal staff notes are never included. */
	public record PublicUpdate(Status status, String note, Instant at) {
	}

	public record MyReport(String reference, Type type, Status status, Long articleId, String articleTitle,
			String description, boolean notifyMe, Instant createdAt, Instant updatedAt, List<PublicUpdate> updates) {
	}

	// --- staff ---

	public record StaffUpdate(Status fromStatus, Status toStatus, String changedBy, String publicNote,
			String internalNote, Instant at) {
	}

	/**
	 * Staff view. The reporter is shown only as a guest or a registered
	 * reader; their identity is not needed to handle the report.
	 */
	public record StaffReport(long id, String reference, Type type, Status status, Resolution resolution,
			Long articleId, String articleTitle, String sourceCode, String reporterType, boolean notifyMe,
			String description, String pageUrl, String uiLanguage, Long assignedTo, String assignedToEmail,
			Instant createdAt, Instant updatedAt, Instant resolvedAt, List<StaffUpdate> history) {
	}

	public record StaffPage(List<StaffReport> items, int page, int size, boolean hasMore) {
	}

	public record StatusRequest(Status status, Resolution resolution, String publicNote, String internalNote) {
	}

	public record AssignRequest(Long assigneeId) {
	}

}
