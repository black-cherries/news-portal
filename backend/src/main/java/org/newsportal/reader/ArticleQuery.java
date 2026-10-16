package org.newsportal.reader;

import org.newsportal.web.InvalidRequestException;

import java.time.LocalDate;
import java.util.List;

/**
 * Filters for the Latest feed and search (FR-023, FR-028, FR-032). Empty lists
 * mean "no filter". Dates are whole UTC days, both inclusive.
 */
public record ArticleQuery(List<String> languages, List<String> excludeLanguages, List<String> categories,
		List<String> sources, LocalDate from, LocalDate to, String text, Cursor after, int size) {

	public static final int DEFAULT_SIZE = 20;

	public static final int MAX_SIZE = 50;

	public static final int MAX_TEXT = 200;

	public static final int MAX_FILTER_VALUES = 20;

	public ArticleQuery {
		languages = clean(languages, "lang", "[a-z]{2,3}");
		excludeLanguages = clean(excludeLanguages, "excludeLang", "[a-z]{2,3}");
		categories = clean(categories, "category", "[a-z_]{1,40}");
		sources = clean(sources, "source", "[a-z0-9_-]{1,60}");
		if (text != null) {
			text = text.strip();
			if (text.isEmpty()) {
				text = null;
			}
			else if (text.length() > MAX_TEXT) {
				throw new InvalidRequestException("q must be at most " + MAX_TEXT + " characters");
			}
		}
		if (from != null && to != null && to.isBefore(from)) {
			throw new InvalidRequestException("to must not be before from");
		}
		if (size == 0) {
			size = DEFAULT_SIZE;
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new InvalidRequestException("size must be between 1 and " + MAX_SIZE);
		}
	}

	private static List<String> clean(List<String> values, String name, String pattern) {
		if (values == null) {
			return List.of();
		}
		List<String> out = values.stream()
			.filter(v -> v != null && !v.isBlank())
			.map(v -> v.strip().toLowerCase(java.util.Locale.ROOT))
			.distinct()
			.toList();
		if (out.size() > MAX_FILTER_VALUES) {
			throw new InvalidRequestException("too many " + name + " values");
		}
		for (String v : out) {
			if (!v.matches(pattern)) {
				throw new InvalidRequestException("invalid " + name + " value");
			}
		}
		return out;
	}

}
