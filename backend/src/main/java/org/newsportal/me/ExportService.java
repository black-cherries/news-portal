package org.newsportal.me;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.newsportal.identity.AccountService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal-data export (FR-049): everything this app stores about the
 * requesting reader, and nothing about anyone else. Saved articles are listed
 * whatever their current rights status, because the reader's own record of
 * them is personal data; only titles and links are included, not content.
 * Secrets (password hash, tokens, session stamp) are never exported.
 */
@Service
@Transactional(readOnly = true)
public class ExportService {

	private final NamedParameterJdbcTemplate jdbc;

	private final MeService me;

	private final AccountService accounts;

	private final Clock clock;

	public ExportService(NamedParameterJdbcTemplate jdbc, MeService me, AccountService accounts, Clock clock) {
		this.jdbc = jdbc;
		this.me = me;
		this.accounts = accounts;
		this.clock = clock;
	}

	public Map<String, Object> export(long userId, String password) {
		if (!accounts.verifyPassword(userId, password)) {
			throw new IllegalArgumentException("password is incorrect");
		}
		var p = new MapSqlParameterSource("uid", userId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("exportedAt", clock.instant().toString());
		out.put("account", jdbc.queryForMap("""
				SELECT email, account_type, status, ui_language, created_at, email_verified_at, age_declared_at,
				       last_login_at, password_changed_at
				FROM app_user WHERE id = :uid
				""", p).entrySet().stream().collect(LinkedHashMap::new,
				(m, e) -> m.put(e.getKey(), text(e.getValue())), Map::putAll));
		out.put("preferences", me.preferences(userId));
		out.put("bookmarks", rows("""
				SELECT a.title, a.canonical_url AS source_url, s.name AS publisher, b.created_at AS saved_at
				FROM bookmark b JOIN article a ON a.id = b.article_id JOIN news_source s ON s.id = a.source_id
				WHERE b.user_id = :uid ORDER BY b.created_at
				""", p));
		List<Map<String, Object>> collections = rows(
				"SELECT id, name, created_at, updated_at FROM collection WHERE user_id = :uid ORDER BY id", p);
		for (Map<String, Object> c : collections) {
			c.put("items", rows("""
					SELECT a.title, a.canonical_url AS source_url, s.name AS publisher, i.added_at
					FROM collection_item i JOIN article a ON a.id = i.article_id JOIN news_source s ON s.id = a.source_id
					WHERE i.collection_id = :cid ORDER BY i.added_at
					""", new MapSqlParameterSource("cid", Long.parseLong(c.get("id").toString()))));
			c.remove("id");
		}
		out.put("collections", collections);
		out.put("history", rows("""
				SELECT h.kind, h.last_at, a.title, a.canonical_url AS source_url
				FROM reading_history h JOIN article a ON a.id = h.article_id
				WHERE h.user_id = :uid ORDER BY h.last_at
				""", p));
		return out;
	}

	private List<Map<String, Object>> rows(String sql, MapSqlParameterSource p) {
		return jdbc.queryForList(sql, p).stream()
			.map(row -> {
				Map<String, Object> m = new LinkedHashMap<>();
				row.forEach((k, v) -> m.put(k, text(v)));
				return m;
			})
			.toList();
	}

	/** Timestamps as ISO-8601 UTC strings; everything else unchanged. */
	private static Object text(Object value) {
		if (value instanceof java.sql.Timestamp ts) {
			return ts.toInstant().toString();
		}
		if (value instanceof OffsetDateTime odt) {
			return odt.toInstant().toString();
		}
		return value;
	}

}
