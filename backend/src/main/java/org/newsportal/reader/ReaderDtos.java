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
			List<String> categories, String imageUrl) {
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
			FullTextStatus fullTextStatus, String fullTextHtml, boolean fullTextNarrationPermitted,
			List<Video> videos, String imageUrl) {
	}

	/**
	 * A video named by the publisher (FR-056). {@code embedUrl} is set only
	 * when VIDEO_EMBED is permitted and the provider is known; otherwise the
	 * app links to {@code sourceUrl}. The app loads an embed only after the
	 * reader asks for it (FR-057).
	 */
	public record Video(String sourceUrl, String provider, String embedUrl, String mimeType) {
	}

	public record Category(String code) {
	}

	public static String direction(String language) {
		return ("ar".equals(language) || "ur".equals(language)) ? "rtl" : "ltr";
	}

}
