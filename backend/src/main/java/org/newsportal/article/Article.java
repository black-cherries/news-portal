package org.newsportal.article;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.newsportal.source.NewsSource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** One publisher's story. Reports from different publishers are never merged (FR-012). */
@Entity
@Table(name = "article")
public class Article {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "source_id", nullable = false)
	private NewsSource source;

	@Column(name = "publisher_guid", length = 1000)
	private String publisherGuid;

	@Column(name = "canonical_url", nullable = false, length = 2000)
	private String canonicalUrl;

	@Column(nullable = false, length = 1000)
	private String title;

	@Column(columnDefinition = "text")
	private String excerpt;

	@Column(length = 500)
	private String author;

	@Column(nullable = false, length = 16)
	private String language;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "publisher_categories", nullable = false, columnDefinition = "text[]")
	private String[] publisherCategories = new String[0];

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "publisher_updated_at")
	private Instant publisherUpdatedAt;

	@Column(name = "first_ingested_at", nullable = false, updatable = false)
	private Instant firstIngestedAt;

	@Column(name = "last_ingested_at", nullable = false)
	private Instant lastIngestedAt;

	@Version
	private long version;

	protected Article() {
	}

	public Article(NewsSource source, String publisherGuid, String canonicalUrl, Instant ingestedAt) {
		this.source = source;
		this.publisherGuid = publisherGuid;
		this.canonicalUrl = canonicalUrl;
		this.firstIngestedAt = ingestedAt;
		this.lastIngestedAt = ingestedAt;
	}

	/**
	 * Applies the latest publisher metadata. Returns true if anything a reader
	 * would see changed.
	 */
	public boolean applyMetadata(String title, String excerpt, String author, String language,
			String[] publisherCategories, Instant publishedAt, Instant publisherUpdatedAt, Instant ingestedAt) {
		boolean changed = !Objects.equals(this.title, title) || !Objects.equals(this.excerpt, excerpt)
				|| !Objects.equals(this.author, author) || !Objects.equals(this.language, language)
				|| !Arrays.equals(this.publisherCategories, publisherCategories)
				|| !Objects.equals(this.publishedAt, publishedAt)
				|| !Objects.equals(this.publisherUpdatedAt, publisherUpdatedAt);
		this.title = title;
		this.excerpt = excerpt;
		this.author = author;
		this.language = language;
		this.publisherCategories = publisherCategories;
		this.publishedAt = publishedAt;
		this.publisherUpdatedAt = publisherUpdatedAt;
		this.lastIngestedAt = ingestedAt;
		return changed;
	}

	public Long getId() {
		return id;
	}

	public NewsSource getSource() {
		return source;
	}

	public String getPublisherGuid() {
		return publisherGuid;
	}

	public String getCanonicalUrl() {
		return canonicalUrl;
	}

	public String getTitle() {
		return title;
	}

	public String getExcerpt() {
		return excerpt;
	}

	public String getAuthor() {
		return author;
	}

	public String getLanguage() {
		return language;
	}

	public String[] getPublisherCategories() {
		return publisherCategories.clone();
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public Instant getPublisherUpdatedAt() {
		return publisherUpdatedAt;
	}

	public Instant getFirstIngestedAt() {
		return firstIngestedAt;
	}

	public Instant getLastIngestedAt() {
		return lastIngestedAt;
	}

}
