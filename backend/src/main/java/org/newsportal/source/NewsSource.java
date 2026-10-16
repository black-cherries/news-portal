package org.newsportal.source;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "news_source")
public class NewsSource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 60)
	private String code;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(name = "homepage_url", nullable = false, length = 500)
	private String homepageUrl;

	@Column(name = "primary_language", nullable = false, length = 8)
	private String primaryLanguage;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SourceStatus status = SourceStatus.PROPOSED;

	@Column(name = "status_reason", length = 500)
	private String statusReason;

	@Column(name = "terms_url", length = 500)
	private String termsUrl;

	@Column(name = "terms_summary", columnDefinition = "text")
	private String termsSummary;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Version
	private long version;

	protected NewsSource() {
	}

	public NewsSource(String code, String name, String homepageUrl, String primaryLanguage) {
		this.code = code;
		this.name = name;
		this.homepageUrl = homepageUrl;
		this.primaryLanguage = primaryLanguage;
	}

	@PreUpdate
	void touch() {
		updatedAt = Instant.now();
	}

	void changeStatus(SourceStatus status, String reason) {
		this.status = status;
		this.statusReason = reason;
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public String getHomepageUrl() {
		return homepageUrl;
	}

	public String getPrimaryLanguage() {
		return primaryLanguage;
	}

	public SourceStatus getStatus() {
		return status;
	}

	public String getStatusReason() {
		return statusReason;
	}

	public String getTermsUrl() {
		return termsUrl;
	}

	public void setTermsUrl(String termsUrl) {
		this.termsUrl = termsUrl;
	}

	public String getTermsSummary() {
		return termsSummary;
	}

	public void setTermsSummary(String termsSummary) {
		this.termsSummary = termsSummary;
	}

}
