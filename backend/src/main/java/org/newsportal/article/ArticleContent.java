package org.newsportal.article;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Sanitized full text. Exists only while STORAGE is permitted (FR-016). */
@Entity
@Table(name = "article_content")
public class ArticleContent {

	@Id
	@Column(name = "article_id")
	private Long articleId;

	@Column(name = "body_html", nullable = false, columnDefinition = "text")
	private String bodyHtml;

	@Column(name = "stored_at", nullable = false)
	private Instant storedAt;

	protected ArticleContent() {
	}

	public ArticleContent(Long articleId, String bodyHtml, Instant storedAt) {
		this.articleId = articleId;
		this.bodyHtml = bodyHtml;
		this.storedAt = storedAt;
	}

	public void replace(String bodyHtml, Instant storedAt) {
		this.bodyHtml = bodyHtml;
		this.storedAt = storedAt;
	}

	public Long getArticleId() {
		return articleId;
	}

	public String getBodyHtml() {
		return bodyHtml;
	}

	public Instant getStoredAt() {
		return storedAt;
	}

}
