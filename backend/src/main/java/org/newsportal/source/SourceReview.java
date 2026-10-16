package org.newsportal.source;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.Immutable;

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

/** One recorded decision about a publisher or one of its content uses. Append-only. */
@Entity
@Immutable
@Table(name = "source_review")
public class SourceReview {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "source_id", nullable = false)
	private NewsSource source;

	@Enumerated(EnumType.STRING)
	@Column(name = "content_use", length = 30)
	private ContentUse contentUse;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ReviewAction action;

	@Column(name = "previous_status", length = 20)
	private String previousStatus;

	@Column(name = "new_status", length = 20)
	private String newStatus;

	@Column(name = "valid_from")
	private Instant validFrom;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(nullable = false, length = 200)
	private String reviewer;

	@Column(nullable = false, columnDefinition = "text")
	private String notes;

	@Column(name = "evidence_url", length = 500)
	private String evidenceUrl;

	@Column(name = "next_review_due")
	private LocalDate nextReviewDue;

	@Column(name = "reviewed_at", nullable = false)
	private Instant reviewedAt;

	protected SourceReview() {
	}

	SourceReview(NewsSource source, ContentUse contentUse, ReviewAction action, String previousStatus,
			String newStatus, String reviewer, String notes, Instant reviewedAt) {
		this.source = source;
		this.contentUse = contentUse;
		this.action = action;
		this.previousStatus = previousStatus;
		this.newStatus = newStatus;
		this.reviewer = reviewer;
		this.notes = notes;
		this.reviewedAt = reviewedAt;
	}

	SourceReview withValidity(Instant validFrom, Instant expiresAt) {
		this.validFrom = validFrom;
		this.expiresAt = expiresAt;
		return this;
	}

	SourceReview withEvidence(String evidenceUrl, LocalDate nextReviewDue) {
		this.evidenceUrl = evidenceUrl;
		this.nextReviewDue = nextReviewDue;
		return this;
	}

	public Long getId() {
		return id;
	}

	public ContentUse getContentUse() {
		return contentUse;
	}

	public ReviewAction getAction() {
		return action;
	}

	public String getPreviousStatus() {
		return previousStatus;
	}

	public String getNewStatus() {
		return newStatus;
	}

	public Instant getValidFrom() {
		return validFrom;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public String getReviewer() {
		return reviewer;
	}

	public String getNotes() {
		return notes;
	}

	public String getEvidenceUrl() {
		return evidenceUrl;
	}

	public LocalDate getNextReviewDue() {
		return nextReviewDue;
	}

	public Instant getReviewedAt() {
		return reviewedAt;
	}

}
