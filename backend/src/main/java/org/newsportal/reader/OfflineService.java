package org.newsportal.reader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.newsportal.source.ContentUse;
import org.newsportal.source.SourceRights;
import org.newsportal.source.SourceRightsRepository;
import org.newsportal.source.SourceRightsService;
import org.newsportal.web.InvalidRequestException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a device may keep for offline reading (FR-061, FR-063, FR-064).
 *
 * - Full text offline needs both FULL_TEXT_DISPLAY and STORAGE, because the
 *   device keeps a copy of the text.
 * - Otherwise the device may keep metadata and the source link, while the
 *   article is shown to readers at all.
 * - Only rights and publisher status count. A failing feed does not
 *   invalidate a download (FR-064).
 * - {@code validUntil} is the earliest relevant rights expiry, and never more
 *   than {@link #RECHECK_PERIOD} away. Devices hide the copy after it until
 *   they have checked again, because restrictions cannot reach a device
 *   while it is offline (FR-063).
 */
@Service
@Transactional(readOnly = true)
public class OfflineService {

	public static final Duration RECHECK_PERIOD = Duration.ofDays(14);

	public static final int MAX_IDS = 200;

	public enum Level {
		/** Title, excerpt, full text and source link may be kept. */
		FULL_TEXT,
		/** Title, excerpt and source link only. */
		METADATA,
		/** Delete the download: the article is no longer shown, or does not exist. */
		NONE
	}

	public record Status(long id, Level level, Instant validUntil) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	private final SourceRightsService rights;

	private final SourceRightsRepository rightsRecords;

	private final Clock clock;

	public OfflineService(NamedParameterJdbcTemplate jdbc, SourceRightsService rights,
			SourceRightsRepository rightsRecords, Clock clock) {
		this.jdbc = jdbc;
		this.rights = rights;
		this.rightsRecords = rightsRecords;
		this.clock = clock;
	}

	public List<Status> status(List<Long> requested) {
		Set<Long> ids = new LinkedHashSet<>(requested == null ? List.of() : requested);
		ids.remove(null);
		if (ids.isEmpty()) {
			return List.of();
		}
		if (ids.size() > MAX_IDS) {
			throw new InvalidRequestException("at most " + MAX_IDS + " ids");
		}
		Instant now = clock.instant();
		record Visible(long sourceId, boolean hasBody) {
		}
		Map<Long, Visible> visible = new HashMap<>();
		jdbc.query("""
				SELECT a.id, a.source_id, EXISTS (SELECT 1 FROM article_content c WHERE c.article_id = a.id) AS has_body
				FROM article a
				""" + ReaderRepository.VISIBLE + " WHERE a.id IN (:ids)",
				new MapSqlParameterSource("ids", ids).addValue("now", now.atOffset(ZoneOffset.UTC)),
				rs -> {
					visible.put(rs.getLong("id"), new Visible(rs.getLong("source_id"), rs.getBoolean("has_body")));
				});

		Map<Long, Boolean> fullTextBySource = new HashMap<>();
		Map<Long, Instant> expiries = new HashMap<>();
		List<Status> out = new ArrayList<>();
		Instant latest = now.plus(RECHECK_PERIOD);
		for (long id : ids) {
			Visible v = visible.get(id);
			if (v == null) {
				out.add(new Status(id, Level.NONE, null));
				continue;
			}
			boolean sourceFullText = fullTextBySource.computeIfAbsent(v.sourceId(),
					s -> rights.check(s, ContentUse.FULL_TEXT_DISPLAY).allowed()
							&& rights.check(s, ContentUse.STORAGE).allowed());
			Level level = (sourceFullText && v.hasBody()) ? Level.FULL_TEXT : Level.METADATA;
			List<ContentUse> uses = (level == Level.FULL_TEXT)
					? List.of(ContentUse.METADATA, ContentUse.FULL_TEXT_DISPLAY, ContentUse.STORAGE)
					: List.of(ContentUse.METADATA);
			Instant until = uses.stream()
				.map(use -> expiry(v.sourceId(), use, expiries))
				.filter(Objects::nonNull)
				.min(Comparator.naturalOrder())
				.filter(latest::isAfter)
				.orElse(latest);
			out.add(new Status(id, level, until));
		}
		return out;
	}

	private Instant expiry(long sourceId, ContentUse use, Map<Long, Instant> cache) {
		// Keyed by source and use; small and short-lived, so a combined key is enough.
		long key = sourceId * 16 + use.ordinal();
		if (cache.containsKey(key)) {
			return cache.get(key);
		}
		Instant expires = rightsRecords.findBySourceIdAndContentUse(sourceId, use)
			.map(SourceRights::getExpiresAt)
			.orElse(null);
		cache.put(key, expires);
		return expires;
	}

}
