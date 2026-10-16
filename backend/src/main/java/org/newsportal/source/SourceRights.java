package org.newsportal.source;

import java.time.Instant;

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Current permission for one publisher and one content use (FR-004). */
@Entity
@Table(name = "source_rights")
public class SourceRights {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "source_id", nullable = false)
	private NewsSource source;

	@Enumerated(EnumType.STRING)
	@Column(name = "content_use", nullable = false, length = 30)
	private ContentUse contentUse;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RightsStatus status = RightsStatus.UNKNOWN;

	@Column(name = "valid_from")
	private Instant validFrom;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(columnDefinition = "text")
	private String conditions;

	@Column(nullable = false)
	private boolean suspended;

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	@Column(name = "suspended_reason", length = 500)
	private String suspendedReason;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "last_review_id")
	private SourceReview lastReview;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected SourceRights() {
	}

	SourceRights(NewsSource source, ContentUse contentUse) {
		this.source = source;
		this.contentUse = contentUse;
	}

	@PreUpdate
	void touch() {
		updatedAt = Instant.now();
	}

	void applyDecision(RightsStatus status, Instant validFrom, Instant expiresAt, String conditions,
			SourceReview review) {
		this.status = status;
		this.validFrom = validFrom;
		this.expiresAt = expiresAt;
		this.conditions = conditions;
		this.lastReview = review;
	}

	void suspend(Instant at, String reason, SourceReview review) {
		this.suspended = true;
		this.suspendedAt = at;
		this.suspendedReason = reason;
		this.lastReview = review;
	}

	void restore(SourceReview review) {
		this.suspended = false;
		this.suspendedAt = null;
		this.suspendedReason = null;
		this.lastReview = review;
	}

	public Long getId() {
		return id;
	}

	public ContentUse getContentUse() {
		return contentUse;
	}

	public RightsStatus getStatus() {
		return status;
	}

	public Instant getValidFrom() {
		return validFrom;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public String getConditions() {
		return conditions;
	}

	public boolean isSuspended() {
		return suspended;
	}

	public Instant getSuspendedAt() {
		return suspendedAt;
	}

	public String getSuspendedReason() {
		return suspendedReason;
	}

}
