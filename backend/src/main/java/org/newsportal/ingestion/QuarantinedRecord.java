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

/** A feed entry that failed validation, kept with its reason (FR-013). */
@Entity
@Immutable
@Table(name = "quarantined_record")
public class QuarantinedRecord {

	public enum Reason {
		MISSING_LINK,
		INVALID_LINK,
		MISSING_TITLE,
		FIELD_TOO_LONG,
		PERSISTENCE_ERROR
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "job_id", nullable = false)
	private Long jobId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private Reason reason;

	@Column(name = "entry_guid", length = 1000)
	private String entryGuid;

	@Column(name = "entry_link", length = 2000)
	private String entryLink;

	@Column(length = 500)
	private String diagnostic;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected QuarantinedRecord() {
	}

	QuarantinedRecord(Long jobId, Quarantine item, Instant createdAt) {
		this.jobId = jobId;
		this.reason = item.reason();
		this.entryGuid = Diagnostics.truncate(item.guid(), 1000);
		this.entryLink = Diagnostics.truncate(item.link(), 2000);
		this.diagnostic = Diagnostics.limit(item.diagnostic());
		this.createdAt = createdAt;
	}

	public Long getJobId() {
		return jobId;
	}

	public Reason getReason() {
		return reason;
	}

	public String getEntryGuid() {
		return entryGuid;
	}

	public String getEntryLink() {
		return entryLink;
	}

	public String getDiagnostic() {
		return diagnostic;
	}

	/** An entry to quarantine, before its job has an id. */
	public record Quarantine(Reason reason, String guid, String link, String diagnostic) {
	}

}
