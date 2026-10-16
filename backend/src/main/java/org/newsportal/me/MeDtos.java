package org.newsportal.me;

import java.time.Instant;
import java.util.List;

import org.newsportal.reader.ReaderDtos.ArticleSummary;

/** JSON shapes for the signed-in reader's own data. */
public final class MeDtos {

	private MeDtos() {
	}

	/**
	 * FR-045: interface language, news languages and digest language are
	 * separate settings. Empty {@code newsLanguages} means "same as interface";
	 * empty {@code topics} means "all topics"; a missing {@code historyPaused} means false.
	 */
	public record Preferences(String uiLanguage, List<String> newsLanguages, List<String> topics,
			String digestLanguage, Boolean historyPaused) {
	}

	public record SavedItem(Instant savedAt, ArticleSummary article) {
	}

	/**
	 * {@code hiddenCount}: saved articles that are not shown because the
	 * publisher's rights no longer allow it (FR-036).
	 */
	public record SavedPage(List<SavedItem> items, int page, int size, boolean hasMore, int hiddenCount) {
	}

	public record CollectionView(long id, String name, int itemCount, Instant createdAt, Instant updatedAt) {
	}

	public record NameRequest(String name) {
	}

	public enum HistoryKind {
		READ, LISTEN
	}

	public record HistoryRequest(long articleId, HistoryKind kind) {
	}

	public record HistoryItem(HistoryKind kind, Instant lastAt, ArticleSummary article) {
	}

	public record HistoryPage(List<HistoryItem> items, int page, int size, boolean hasMore, boolean paused) {
	}

}
