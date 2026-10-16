package org.newsportal.ingestion;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Record of one fetch attempt (FR-010). */
@Entity
@Immutable
@Table(name = "ingestion_job")
public class IngestionJob {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "feed_id", nullable = false)
	private Long feedId;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt;

	@Column(name = "finished_at", nullable = false)
	private Instant finishedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private FeedOutcome outcome;

	@Column(name = "http_status")
	private Integer httpStatus;

	@Column(name = "items_seen", nullable = false)
	private int itemsSeen;

	@Column(name = "items_created", nullable = false)
	private int itemsCreated;

	@Column(name = "items_updated", nullable = false)
	private int itemsUpdated;

	@Column(name = "items_unchanged", nullable = false)
	private int itemsUnchanged;

	@Column(name = "items_quarantined", nullable = false)
	private int itemsQuarantined;

	@Column(length = 500)
	private String diagnostic;

	protected IngestionJob() {
	}

	IngestionJob(Long feedId, Instant startedAt, Instant finishedAt, FeedOutcome outcome, Integer httpStatus,
			ItemCounts counts, String diagnostic) {
		this.feedId = feedId;
		this.startedAt = startedAt;
		this.finishedAt = finishedAt;
		this.outcome = outcome;
		this.httpStatus = httpStatus;
		this.itemsSeen = counts.seen();
		this.itemsCreated = counts.created();
		this.itemsUpdated = counts.updated();
		this.itemsUnchanged = counts.unchanged();
		this.itemsQuarantined = counts.quarantined();
		this.diagnostic = Diagnostics.limit(diagnostic);
	}

	public Long getId() {
		return id;
	}

	public Long getFeedId() {
		return feedId;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public FeedOutcome getOutcome() {
		return outcome;
	}

	public Integer getHttpStatus() {
		return httpStatus;
	}

	public int getItemsSeen() {
		return itemsSeen;
	}

	public int getItemsCreated() {
		return itemsCreated;
	}

	public int getItemsUpdated() {
		return itemsUpdated;
	}

	public int getItemsUnchanged() {
		return itemsUnchanged;
	}

	public int getItemsQuarantined() {
		return itemsQuarantined;
	}

	public String getDiagnostic() {
		return diagnostic;
	}

	/** Per-item tallies for one job. */
	public record ItemCounts(int seen, int created, int updated, int unchanged, int quarantined) {

		static final ItemCounts NONE = new ItemCounts(0, 0, 0, 0, 0);

	}

}
