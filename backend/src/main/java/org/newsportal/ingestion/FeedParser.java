package org.newsportal.ingestion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import org.springframework.stereotype.Component;

/** Parses RSS/Atom bytes into plain entries. Rome rejects DOCTYPE declarations by default. */
@Component
public class FeedParser {

	public ParsedFeed parse(byte[] body) throws MalformedFeedException {
		SyndFeed feed;
		try (XmlReader reader = new XmlReader(new ByteArrayInputStream(body))) {
			SyndFeedInput input = new SyndFeedInput();
			input.setAllowDoctypes(false);
			feed = input.build(reader);
		}
		catch (FeedException | IOException | IllegalArgumentException ex) {
			throw new MalformedFeedException(ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
		}
		String language = normalizeLanguage(feed.getLanguage());
		List<FeedEntry> entries = feed.getEntries().stream().map(FeedParser::toEntry).toList();
		return new ParsedFeed(language, entries);
	}

	private static FeedEntry toEntry(SyndEntry e) {
		String description = (e.getDescription() == null) ? null : e.getDescription().getValue();
		String content = e.getContents().stream()
			.map(SyndContent::getValue)
			.filter(v -> v != null && !v.isBlank())
			.findFirst()
			.orElse(null);
		List<String> categories = e.getCategories().stream()
			.map(SyndCategory::getName)
			.filter(n -> n != null && !n.isBlank())
			.map(String::strip)
			.distinct()
			.toList();
		return new FeedEntry(blankToNull(e.getUri()), blankToNull(e.getLink()), e.getTitle(), description, content,
				blankToNull(e.getAuthor()), toInstant(e.getPublishedDate()), toInstant(e.getUpdatedDate()),
				categories);
	}

	/** "en-US" → "en". Null if absent. */
	static String normalizeLanguage(String tag) {
		if (tag == null || tag.isBlank()) {
			return null;
		}
		String primary = tag.strip().split("[-_]", 2)[0].toLowerCase(Locale.ROOT);
		return primary.matches("[a-z]{2,3}") ? primary : null;
	}

	private static Instant toInstant(Date date) {
		return (date == null) ? null : date.toInstant();
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

	public record ParsedFeed(String language, List<FeedEntry> entries) {
	}

	/** One entry as the publisher supplied it. Times are null when the feed does not give them (FR-022). */
	public record FeedEntry(String guid, String link, String title, String descriptionHtml, String contentHtml,
			String author, Instant publishedAt, Instant updatedAt, List<String> categories) {
	}

	public static class MalformedFeedException extends Exception {

		MalformedFeedException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
