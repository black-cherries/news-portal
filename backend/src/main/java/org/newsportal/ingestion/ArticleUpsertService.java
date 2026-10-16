package org.newsportal.ingestion;

import java.time.Instant;
import java.util.Optional;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.newsportal.article.Article;
import org.newsportal.article.ArticleContent;
import org.newsportal.article.ArticleContentRepository;
import org.newsportal.article.ArticleRepository;
import org.newsportal.ingestion.FeedParser.FeedEntry;
import org.newsportal.source.NewsSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores one validated entry. Each call runs in its own transaction so one bad
 * entry cannot roll back the rest of the feed (FR-013).
 */
@Service
public class ArticleUpsertService {

	static final int MAX_EXCERPT = 1000;

	private final ArticleRepository articles;

	private final ArticleContentRepository contents;

	private final NewsSourceRepository sources;

	private final CategoryAssigner categories;

	public ArticleUpsertService(ArticleRepository articles, ArticleContentRepository contents,
			NewsSourceRepository sources, CategoryAssigner categories) {
		this.articles = articles;
		this.contents = contents;
		this.sources = sources;
		this.categories = categories;
	}

	public enum Result {
		CREATED, UPDATED, UNCHANGED
	}

	/**
	 * @param canonicalUrl already validated
	 * @param storeContent whether STORAGE is currently permitted for this source
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Result upsert(Long sourceId, FeedEntry entry, String canonicalUrl, String language, boolean storeContent,
			Instant now) {
		// FR-011: look up by publisher id first, then by canonical URL; update instead of duplicating.
		Optional<Article> existing = Optional.empty();
		if (entry.guid() != null) {
			existing = articles.findBySourceIdAndPublisherGuid(sourceId, entry.guid());
		}
		if (existing.isEmpty()) {
			existing = articles.findBySourceIdAndCanonicalUrl(sourceId, canonicalUrl);
		}
		boolean created = existing.isEmpty();
		Article article = existing.orElseGet(
				() -> new Article(sources.getReferenceById(sourceId), entry.guid(), canonicalUrl, now));

		boolean changed = article.applyMetadata(entry.title().strip(), toExcerpt(entry.descriptionHtml()),
				entry.author(), language, entry.categories().toArray(String[]::new), entry.publishedAt(),
				entry.updatedAt(), now);
		article = articles.save(article);
		changed |= syncContent(article.getId(), entry.contentHtml(), storeContent, now);
		categories.assign(article.getId(), sourceId, entry.categories());

		if (created) {
			return Result.CREATED;
		}
		return changed ? Result.UPDATED : Result.UNCHANGED;
	}

	/** Stores sanitized full text only when permitted; removes it once it no longer is (FR-016, FR-036). */
	private boolean syncContent(Long articleId, String contentHtml, boolean storeContent, Instant now) {
		Optional<ArticleContent> stored = contents.findById(articleId);
		if (!storeContent || contentHtml == null) {
			if (stored.isPresent() && !storeContent) {
				contents.delete(stored.get());
				return true;
			}
			return false;
		}
		String clean = Jsoup.clean(contentHtml, Safelist.basic());
		if (stored.isPresent()) {
			if (stored.get().getBodyHtml().equals(clean)) {
				return false;
			}
			stored.get().replace(clean, now);
			return true;
		}
		contents.save(new ArticleContent(articleId, clean, now));
		return true;
	}

	static String toExcerpt(String html) {
		if (html == null) {
			return null;
		}
		String text = Jsoup.parse(html).text().strip();
		if (text.isEmpty()) {
			return null;
		}
		return (text.length() <= MAX_EXCERPT) ? text : text.substring(0, MAX_EXCERPT - 1) + "…";
	}

}
