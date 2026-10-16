package org.newsportal.translate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.newsportal.identity.RateLimiter;
import org.newsportal.reader.ReaderDtos;
import org.newsportal.reader.ReaderRepository;
import org.newsportal.source.ContentUse;
import org.newsportal.source.SourceRightsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * On-demand machine translation of articles (owner decision of 2026-10-16;
 * SRS amendment 2).
 *
 * - Needs the publisher's TRANSLATION permission, checked every time a
 *   translation is made or shown.
 * - The body is translated and shown only while full-text display is
 *   permitted too; otherwise title and summary only.
 * - Each article is translated once per language and cached. A changed
 *   original, or a change in what may be translated, makes a new one.
 * - Readers ask for new translations; anyone can read a cached one.
 * - Cost limits: per reader per hour and per day across the app.
 *
 * Not transactional around the model call, which can take many seconds.
 */
@Service
@EnableConfigurationProperties(TranslationProperties.class)
public class TranslationService {

	public static final Set<String> LANGUAGES = Set.of("en", "hi", "ur", "ar");

	private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

	public record TranslationView(long articleId, String sourceLanguage, String language, String direction,
			String title, String excerpt, String bodyHtml, boolean machineTranslated, String model,
			Instant translatedAt) {
	}

	/** Translation is switched off (no API key). */
	public static class TranslationUnavailableException extends RuntimeException {

		TranslationUnavailableException() {
			super("translation is not available right now");
		}

	}

	/** The app-wide daily limit is reached. */
	public static class DailyLimitException extends RuntimeException {

		DailyLimitException() {
			super("the daily translation limit has been reached; try again tomorrow");
		}

	}

	private final NamedParameterJdbcTemplate jdbc;

	private final SourceRightsService rights;

	private final Translator translator;

	private final TranslationProperties props;

	private final RateLimiter limiter;

	private final Clock clock;

	public TranslationService(NamedParameterJdbcTemplate jdbc, SourceRightsService rights, Translator translator,
			TranslationProperties props, RateLimiter limiter, Clock clock) {
		this.jdbc = jdbc;
		this.rights = rights;
		this.translator = translator;
		this.props = props;
		this.limiter = limiter;
		this.clock = clock;
	}

	/** Whether articles from this publisher can be translated now. */
	public boolean available(long sourceId) {
		return translator.available() && rights.check(sourceId, ContentUse.TRANSLATION).allowed();
	}

	/** A cached translation that matches the current original and permissions. */
	public Optional<TranslationView> cached(long articleId, String language) {
		Source source = source(articleId, checkLanguage(language));
		if (!rights.check(source.sourceId(), ContentUse.TRANSLATION).allowed()) {
			return Optional.empty();
		}
		return stored(source, language);
	}

	/** Returns the cached translation or makes a new one. Readers only (see SecurityConfig). */
	public TranslationView translate(long articleId, String language, long readerId) {
		Source source = source(articleId, checkLanguage(language));
		if (source.language().equals(language)) {
			throw new IllegalArgumentException("the article is already in this language");
		}
		if (!translator.available()) {
			throw new TranslationUnavailableException();
		}
		if (!rights.check(source.sourceId(), ContentUse.TRANSLATION).allowed()) {
			throw new IllegalStateException("the publisher does not allow translation of this article");
		}
		Optional<TranslationView> cached = stored(source, language);
		if (cached.isPresent()) {
			return cached.get();
		}

		limiter.acquire("translate:" + readerId, props.maxPerReaderPerHour(), Duration.ofHours(1));
		Instant now = clock.instant();
		Integer today = jdbc.queryForObject("SELECT count(*) FROM article_translation WHERE translated_at > :since",
				new MapSqlParameterSource("since", now.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC)),
				Integer.class);
		if (today != null && today >= props.maxPerDay()) {
			throw new DailyLimitException();
		}

		Translator.Result result = translator.translate(source.language(), language, source.title(), source.excerpt(),
				source.translatableBody());
		String title = plain(result.title());
		String excerpt = (source.excerpt() == null) ? null : plain(result.excerpt());
		// Translated HTML is cleaned exactly like the publisher's original (FR-016).
		String body = (source.translatableBody() == null || result.bodyHtml() == null) ? null
				: Jsoup.clean(result.bodyHtml(), Safelist.basic());
		if (title == null || title.isBlank()) {
			throw new Translator.TranslationFailedException("translation has no title", null);
		}

		jdbc.update("""
				INSERT INTO article_translation (article_id, language, title, excerpt, body_html, source_hash, model,
				                                 translated_at)
				VALUES (:aid, :lang, :title, :excerpt, :body, :hash, :model, :now)
				ON CONFLICT (article_id, language) DO UPDATE SET title = EXCLUDED.title, excerpt = EXCLUDED.excerpt,
				    body_html = EXCLUDED.body_html, source_hash = EXCLUDED.source_hash, model = EXCLUDED.model,
				    translated_at = EXCLUDED.translated_at
				""", new MapSqlParameterSource("aid", articleId).addValue("lang", language)
			.addValue("title", truncate(title, 2000))
			.addValue("excerpt", excerpt)
			.addValue("body", body)
			.addValue("hash", source.hash())
			.addValue("model", translator.model())
			.addValue("now", now.atOffset(ZoneOffset.UTC)));
		log.info("Translated article {} into {}", articleId, language);
		return view(source, language, title, excerpt, body, translator.model(), now);
	}

	// --- helpers ---

	/** The original as it may be translated right now. */
	private record Source(long articleId, long sourceId, String language, String title, String excerpt,
			String translatableBody, String hash) {
	}

	private Source source(long articleId, String targetLanguage) {
		Instant now = clock.instant();
		List<Source> rows = jdbc.query("""
				SELECT a.id, a.source_id, a.language, a.title, a.excerpt, c.body_html
				FROM article a
				""" + ReaderRepository.VISIBLE + """
				LEFT JOIN article_content c ON c.article_id = a.id
				WHERE a.id = :id
				""", new MapSqlParameterSource("id", articleId).addValue("now", now.atOffset(ZoneOffset.UTC)),
				(rs, n) -> source(rs));
		if (rows.isEmpty()) {
			throw new NoSuchElementException("article not found");
		}
		return rows.getFirst();
	}

	private Source source(ResultSet rs) throws SQLException {
		long sourceId = rs.getLong("source_id");
		String body = rs.getString("body_html");
		boolean bodyAllowed = body != null && body.length() <= props.maxBodyChars()
				&& rights.check(sourceId, ContentUse.FULL_TEXT_DISPLAY).allowed();
		String translatable = bodyAllowed ? body : null;
		String title = rs.getString("title");
		String excerpt = rs.getString("excerpt");
		return new Source(rs.getLong("id"), sourceId, rs.getString("language"), title, excerpt, translatable,
				sha256(title + '\u0000' + (excerpt == null ? "" : excerpt) + '\u0000'
						+ (translatable == null ? "" : translatable)));
	}

	private Optional<TranslationView> stored(Source source, String language) {
		return jdbc.query("""
				SELECT title, excerpt, body_html, model, translated_at FROM article_translation
				WHERE article_id = :aid AND language = :lang AND source_hash = :hash
				""", new MapSqlParameterSource("aid", source.articleId()).addValue("lang", language)
			.addValue("hash", source.hash()),
				(rs, n) -> view(source, language, rs.getString("title"), rs.getString("excerpt"),
						// The body only while full-text display is still permitted.
						source.translatableBody() == null ? null : rs.getString("body_html"), rs.getString("model"),
						ReaderRepository.instant(rs, "translated_at")))
			.stream()
			.findFirst();
	}

	private static TranslationView view(Source source, String language, String title, String excerpt, String body,
			String model, Instant at) {
		return new TranslationView(source.articleId(), source.language(), language, ReaderDtos.direction(language),
				title, excerpt, body, true, model, at);
	}

	private static String checkLanguage(String language) {
		if (language == null || !LANGUAGES.contains(language)) {
			throw new IllegalArgumentException("language must be one of " + LANGUAGES);
		}
		return language;
	}

	/** Model output is untrusted: titles and summaries are plain text only. */
	private static String plain(String text) {
		return (text == null) ? null : Jsoup.parse(text).text().strip();
	}

	private static String truncate(String text, int max) {
		return (text.length() <= max) ? text : text.substring(0, max);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
