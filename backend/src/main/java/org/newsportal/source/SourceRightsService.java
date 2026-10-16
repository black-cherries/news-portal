package org.newsportal.source;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publisher rights management (SRS §5.1). Every change writes a
 * {@link SourceReview} so decisions stay auditable.
 *
 * Callers are responsible for authorizing the acting staff member; the REST
 * layer will restrict these operations to source managers (FR-074).
 */
@Service
@Transactional
public class SourceRightsService {

	private final NewsSourceRepository sources;

	private final SourceRightsRepository rights;

	private final SourceReviewRepository reviews;

	private final Clock clock;

	public SourceRightsService(NewsSourceRepository sources, SourceRightsRepository rights,
			SourceReviewRepository reviews, Clock clock) {
		this.sources = sources;
		this.rights = rights;
		this.reviews = reviews;
		this.clock = clock;
	}

	/** The single gate every content use must pass (FR-005, FR-007). */
	@Transactional(readOnly = true)
	public RightsDecision check(Long sourceId, ContentUse use) {
		NewsSource source = sources.findById(sourceId).orElse(null);
		SourceRights record = (source == null) ? null
				: rights.findBySourceIdAndContentUse(sourceId, use).orElse(null);
		return RightsPolicy.evaluate(source, record, clock.instant());
	}

	public NewsSource proposeSource(String code, String name, String homepageUrl, String primaryLanguage) {
		return sources.save(new NewsSource(code, name, homepageUrl, primaryLanguage));
	}

	/** Records a rights review. It does not lift a suspension; use {@link #restoreUse}. */
	public SourceRights recordRightsDecision(Long sourceId, ContentUse use, RightsReview review) {
		requireText(review.reviewer(), "reviewer");
		requireText(review.notes(), "notes");
		if (review.status() == null) {
			throw new IllegalArgumentException("status is required");
		}
		Instant now = clock.instant();
		if (review.validFrom() != null && review.expiresAt() != null
				&& !review.expiresAt().isAfter(review.validFrom())) {
			throw new IllegalArgumentException("expiresAt must be after validFrom");
		}
		if (review.status() == RightsStatus.PERMITTED && review.expiresAt() != null
				&& !review.expiresAt().isAfter(now)) {
			throw new IllegalArgumentException("cannot grant a permission that has already expired");
		}

		NewsSource source = findSource(sourceId);
		SourceRights record = rights.findBySourceIdAndContentUse(sourceId, use)
			.orElseGet(() -> new SourceRights(source, use));
		String previous = (record.getId() == null) ? null : record.getStatus().name();

		SourceReview entry = reviews.save(new SourceReview(source, use, ReviewAction.RIGHTS_DECISION, previous,
				review.status().name(), review.reviewer(), review.notes(), now)
			.withValidity(review.validFrom(), review.expiresAt())
			.withEvidence(review.evidenceUrl(), review.nextReviewDue()));
		record.applyDecision(review.status(), review.validFrom(), review.expiresAt(), review.conditions(), entry);
		return rights.save(record);
	}

	/** Blocks one use immediately, whatever its rights status (FR-006). */
	public SourceRights suspendUse(Long sourceId, ContentUse use, String reviewer, String reason) {
		requireText(reviewer, "reviewer");
		requireText(reason, "reason");
		NewsSource source = findSource(sourceId);
		SourceRights record = rights.findBySourceIdAndContentUse(sourceId, use)
			.orElseGet(() -> new SourceRights(source, use));
		if (record.isSuspended()) {
			throw new IllegalStateException(use + " is already suspended");
		}
		Instant now = clock.instant();
		SourceReview entry = reviews.save(new SourceReview(source, use, ReviewAction.USE_SUSPENDED,
				"ACTIVE", "SUSPENDED", reviewer, reason, now));
		record.suspend(now, reason, entry);
		return rights.save(record);
	}

	/**
	 * Lifts a suspension. The call is the recorded approval (FR-006). The use is
	 * still allowed only if its rights status is PERMITTED and in date.
	 */
	public SourceRights restoreUse(Long sourceId, ContentUse use, String approver, String approvalNotes) {
		requireText(approver, "approver");
		requireText(approvalNotes, "approvalNotes");
		NewsSource source = findSource(sourceId);
		SourceRights record = rights.findBySourceIdAndContentUse(sourceId, use)
			.filter(SourceRights::isSuspended)
			.orElseThrow(() -> new IllegalStateException(use + " is not suspended"));
		SourceReview entry = reviews.save(new SourceReview(source, use, ReviewAction.USE_RESTORED,
				"SUSPENDED", "ACTIVE", approver, approvalNotes, clock.instant()));
		record.restore(entry);
		return rights.save(record);
	}

	/** Moves a proposed publisher to ACTIVE. Metadata use must already be permitted (FR-002). */
	public NewsSource activateSource(Long sourceId, String approver, String notes) {
		NewsSource source = findSource(sourceId);
		requireStatus(source, SourceStatus.PROPOSED);
		requireMetadataPermitted(source);
		return changeSourceStatus(source, SourceStatus.ACTIVE, ReviewAction.SOURCE_ACTIVATED, approver, notes);
	}

	/** Blocks every use of the publisher's content (FR-006). */
	public NewsSource suspendSource(Long sourceId, String reviewer, String reason) {
		NewsSource source = findSource(sourceId);
		requireStatus(source, SourceStatus.ACTIVE);
		return changeSourceStatus(source, SourceStatus.SUSPENDED, ReviewAction.SOURCE_SUSPENDED, reviewer, reason);
	}

	/** Re-activates a suspended publisher. Metadata use must still be permitted. */
	public NewsSource restoreSource(Long sourceId, String approver, String approvalNotes) {
		NewsSource source = findSource(sourceId);
		requireStatus(source, SourceStatus.SUSPENDED);
		requireMetadataPermitted(source);
		return changeSourceStatus(source, SourceStatus.ACTIVE, ReviewAction.SOURCE_RESTORED, approver,
				approvalNotes);
	}

	public NewsSource retireSource(Long sourceId, String reviewer, String reason) {
		NewsSource source = findSource(sourceId);
		if (source.getStatus() == SourceStatus.RETIRED) {
			throw new IllegalStateException("source is already retired");
		}
		return changeSourceStatus(source, SourceStatus.RETIRED, ReviewAction.SOURCE_RETIRED, reviewer, reason);
	}

	private NewsSource changeSourceStatus(NewsSource source, SourceStatus target, ReviewAction action,
			String reviewer, String notes) {
		requireText(reviewer, "reviewer");
		requireText(notes, "notes");
		reviews.save(new SourceReview(source, null, action, source.getStatus().name(), target.name(), reviewer,
				notes, clock.instant()));
		source.changeStatus(target, notes);
		return sources.save(source);
	}

	private void requireMetadataPermitted(NewsSource source) {
		SourceRights metadata = rights.findBySourceIdAndContentUse(source.getId(), ContentUse.METADATA)
			.orElse(null);
		RightsDecision decision = RightsPolicy.evaluateUse(metadata, clock.instant());
		if (!decision.allowed()) {
			throw new IllegalStateException("METADATA use is not permitted: " + decision.reason());
		}
	}

	private static void requireStatus(NewsSource source, SourceStatus expected) {
		if (source.getStatus() != expected) {
			throw new IllegalStateException("source is " + source.getStatus() + ", expected " + expected);
		}
	}

	private NewsSource findSource(Long sourceId) {
		return sources.findById(sourceId)
			.orElseThrow(() -> new NoSuchElementException("news source " + sourceId + " not found"));
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
	}

}
