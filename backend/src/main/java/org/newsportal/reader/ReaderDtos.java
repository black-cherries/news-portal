package org.newsportal.reader;

import java.time.Instant;
import java.util.List;

/** JSON shapes for the public reader API. */
public final class ReaderDtos {

	private ReaderDtos() {
	}

	public record Publisher(String code, String name, String homepageUrl) {
	}

	/**
	 * Feed/search item. FR-017/FR-022: publisher time, publisher update time and
	 * ingestion time are separate; null means the publisher did not say.
	 */
	public record ArticleSummary(long id, String title, String excerpt, Publisher publisher, String sourceUrl,
			String language, String direction, Instant publishedAt, Instant publisherUpdatedAt, Instant ingestedAt,
			List<String> categories) {
	}

	public record ArticlePage(List<ArticleSummary> items, String nextCursor) {
	}

	public enum FullTextStatus {
		/** Full text is permitted and stored; see {@code fullTextHtml}. */
		AVAILABLE,
		/** The publisher has not permitted full-text display; read it at the source. */
		NOT_PERMITTED,
		/** Display is permitted but the feed did not provide full text. */
		NOT_PROVIDED
	}

	/**
	 * {@code fullTextNarrationPermitted}: text-to-speech may read the full
	 * text. When false, only the title and excerpt may be read aloud (FR-059).
	 */
	public record ArticleDetail(long id, String title, String excerpt, String author, Publisher publisher,
			String sourceUrl, String language, String direction, Instant publishedAt, Instant publisherUpdatedAt,
			Instant ingestedAt, Instant lastSeenAt, List<String> categories, List<String> publisherCategories,
			FullTextStatus fullTextStatus, String fullTextHtml, boolean fullTextNarrationPermitted) {
	}

	public record Category(String code) {
	}

	public static String direction(String language) {
		return ("ar".equals(language) || "ur".equals(language)) ? "rtl" : "ltr";
	}

}
