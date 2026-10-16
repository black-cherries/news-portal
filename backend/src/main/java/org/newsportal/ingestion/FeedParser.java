package org.newsportal.ingestion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import org.jdom2.Element;
import org.newsportal.reader.VideoLinks;
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
				categories, videos(e), image(e));
	}

	/**
	 * The entry's lead image: {@code media:thumbnail}, then an image
	 * {@code media:content} (also inside {@code media:group}), then an image
	 * enclosure. HTTPS only, so pages never load mixed content. Shown only
	 * where the publisher grants the IMAGE permission.
	 */
	static String image(SyndEntry e) {
		String thumbnail = null;
		String content = null;
		for (Element element : e.getForeignMarkup()) {
			for (Element m : mediaElements(element)) {
				String url = httpsUrl(m.getAttributeValue("url"));
				if (url == null) {
					continue;
				}
				if (thumbnail == null && m.getName().equals("thumbnail")) {
					thumbnail = url;
				}
				String type = m.getAttributeValue("type");
				boolean isImage = "image".equalsIgnoreCase(m.getAttributeValue("medium"))
						|| (type != null && type.toLowerCase(Locale.ROOT).startsWith("image/"));
				if (content == null && m.getName().equals("content") && isImage) {
					content = url;
				}
			}
		}
		if (thumbnail != null) {
			return thumbnail;
		}
		if (content != null) {
			return content;
		}
		return e.getEnclosures().stream()
			.filter(enc -> enc.getType() != null && enc.getType().toLowerCase(Locale.ROOT).startsWith("image/"))
			.map(enc -> httpsUrl(enc.getUrl()))
			.filter(u -> u != null)
			.findFirst()
			.orElse(null);
	}

	/** The element itself and, for media:group, its children; Media RSS only. */
	private static List<Element> mediaElements(Element element) {
		if (!MEDIA_RSS.equals(element.getNamespaceURI())) {
			return List.of();
		}
		if (element.getName().equals("group")) {
			return element.getChildren().stream().filter(c -> MEDIA_RSS.equals(c.getNamespaceURI())).toList();
		}
		return List.of(element);
	}

	private static String httpsUrl(String raw) {
		String url = blankToNull(raw);
		return (url != null && url.length() <= 2000 && url.matches("(?i)^https://\\S+$")) ? url : null;
	}

	/**
	 * FR-056: videos the publisher names for this entry, from enclosures, Media
	 * RSS ({@code media:content}, {@code media:group}, {@code media:player}) or
	 * an entry link that is itself a known video page. Links only; at most
	 * {@link #MAX_VIDEOS}.
	 */
	static List<FeedVideo> videos(SyndEntry e) {
		Map<String, FeedVideo> found = new LinkedHashMap<>();
		for (SyndEnclosure enclosure : e.getEnclosures()) {
			addVideo(found, enclosure.getUrl(), enclosure.getType(), false);
		}
		for (Element element : e.getForeignMarkup()) {
			collectMedia(element, found);
		}
		addVideo(found, e.getLink(), null, false);
		return found.values().stream().limit(MAX_VIDEOS).toList();
	}

	static final int MAX_VIDEOS = 5;

	private static final String MEDIA_RSS = "http://search.yahoo.com/mrss/";

	private static void collectMedia(Element element, Map<String, FeedVideo> found) {
		if (!MEDIA_RSS.equals(element.getNamespaceURI())) {
			return;
		}
		switch (element.getName()) {
			case "group" -> element.getChildren().forEach(child -> collectMedia(child, found));
			case "content" -> addVideo(found, element.getAttributeValue("url"), element.getAttributeValue("type"),
					"video".equalsIgnoreCase(element.getAttributeValue("medium")));
			// A player address is usually a web page: only known providers count.
			case "player" -> addVideo(found, element.getAttributeValue("url"), null, false);
			default -> {
			}
		}
	}

	private static void addVideo(Map<String, FeedVideo> found, String rawUrl, String rawType, boolean declaredVideo) {
		String url = blankToNull(rawUrl);
		if (url == null || url.length() > 2000 || !url.matches("(?i)^https?://\\S+$")) {
			return;
		}
		String type = blankToNull(rawType);
		if (type != null && (type.length() > 100 || !type.matches("[A-Za-z0-9.+/-]+(;.*)?"))) {
			type = null;
		}
		if (type != null) {
			type = type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
		}
		if (declaredVideo || VideoLinks.isVideo(url, type)) {
			found.putIfAbsent(url, new FeedVideo(url, type));
		}
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
			String author, Instant publishedAt, Instant updatedAt, List<String> categories, List<FeedVideo> videos,
			String imageUrl) {

		public FeedEntry {
			videos = (videos == null) ? List.of() : List.copyOf(videos);
		}

		/** An entry without videos or image. */
		public FeedEntry(String guid, String link, String title, String descriptionHtml, String contentHtml,
				String author, Instant publishedAt, Instant updatedAt, List<String> categories) {
			this(guid, link, title, descriptionHtml, contentHtml, author, publishedAt, updatedAt, categories, List.of(),
					null);
		}

		/** An entry without an image. */
		public FeedEntry(String guid, String link, String title, String descriptionHtml, String contentHtml,
				String author, Instant publishedAt, Instant updatedAt, List<String> categories, List<FeedVideo> videos) {
			this(guid, link, title, descriptionHtml, contentHtml, author, publishedAt, updatedAt, categories, videos,
					null);
		}

	}

	/** A video link from the feed. {@code mimeType} is null when the feed does not say. */
	public record FeedVideo(String url, String mimeType) {
	}

	public static class MalformedFeedException extends Exception {

		MalformedFeedException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
