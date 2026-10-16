package org.newsportal.reader;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.newsportal.reader.ReaderDtos.ArticleDetail;
import org.newsportal.reader.ReaderDtos.ArticleSummary;
import org.newsportal.reader.ReaderDtos.FullTextStatus;
import org.newsportal.reader.ReaderDtos.Publisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-side SQL for readers. Every query joins {@link #VISIBLE}, so articles
 * whose publisher is inactive or whose METADATA permission is missing,
 * suspended, expired or not yet valid never leave the database (FR-005, FR-036).
 * Keep this in step with {@code RightsPolicy}.
 */
@Repository
public class ReaderRepository {

	public static final String VISIBLE = """
			JOIN news_source s ON s.id = a.source_id AND s.status = 'ACTIVE'
			JOIN source_rights r ON r.source_id = a.source_id AND r.content_use = 'METADATA'
			     AND r.status = 'PERMITTED' AND NOT r.suspended
			     AND (r.valid_from IS NULL OR r.valid_from <= :now)
			     AND (r.expires_at IS NULL OR r.expires_at > :now)
			""";

	public static final String CATEGORIES = """
			ARRAY(SELECT c.code FROM article_category ac JOIN category c ON c.id = ac.category_id
			      WHERE ac.article_id = a.id AND c.active ORDER BY c.sort_order) AS categories
			""";

	/**
	 * The lead image only while the publisher's IMAGE permission is valid;
	 * otherwise null. Needs {@code :now}. Keep in step with {@code RightsPolicy}.
	 */
	public static final String IMAGE = """
			CASE WHEN EXISTS (SELECT 1 FROM source_rights ri
			                  WHERE ri.source_id = a.source_id AND ri.content_use = 'IMAGE'
			                    AND ri.status = 'PERMITTED' AND NOT ri.suspended
			                    AND (ri.valid_from IS NULL OR ri.valid_from <= :now)
			                    AND (ri.expires_at IS NULL OR ri.expires_at > :now))
			     THEN a.image_url END AS image_url
			""";

	// Query words are matched unstemmed and with each available stemmer, since
	// the reader's query language is not known.
	public static final String SEARCH = """
			a.search_vector @@ (websearch_to_tsquery('simple', :q) || websearch_to_tsquery('english', :q)
			                    || websearch_to_tsquery('hindi', :q) || websearch_to_tsquery('arabic', :q))
			""";

	private final NamedParameterJdbcTemplate jdbc;

	public ReaderRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Returns up to {@code limit} items, newest first. */
	public List<Row> find(ArticleQuery query, Instant now, int limit) {
		StringBuilder sql = new StringBuilder("""
				SELECT a.id, a.title, a.excerpt, a.canonical_url, a.language, a.published_at,
				       a.publisher_updated_at, a.first_ingested_at, a.sort_at,
				       s.code AS source_code, s.name AS source_name, s.homepage_url,
				""").append(CATEGORIES).append(", ").append(IMAGE).append(" FROM article a ").append(VISIBLE).append(" WHERE TRUE ");
		MapSqlParameterSource p = new MapSqlParameterSource("now", utc(now)).addValue("limit", limit);

		if (!query.languages().isEmpty()) {
			sql.append(" AND a.language IN (:languages)");
			p.addValue("languages", query.languages());
		}
		if (!query.excludeLanguages().isEmpty()) {
			sql.append(" AND a.language NOT IN (:excludeLanguages)");
			p.addValue("excludeLanguages", query.excludeLanguages());
		}
		if (!query.sources().isEmpty()) {
			sql.append(" AND s.code IN (:sources)");
			p.addValue("sources", query.sources());
		}
		if (!query.categories().isEmpty()) {
			sql.append("""
					 AND EXISTS (SELECT 1 FROM article_category ac JOIN category c ON c.id = ac.category_id
					             WHERE ac.article_id = a.id AND c.code IN (:categories))
					""");
			p.addValue("categories", query.categories());
		}
		if (query.from() != null) {
			sql.append(" AND a.sort_at >= :from");
			p.addValue("from", query.from().atStartOfDay().atOffset(ZoneOffset.UTC));
		}
		if (query.to() != null) {
			sql.append(" AND a.sort_at < :toExclusive");
			p.addValue("toExclusive", query.to().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
		}
		if (query.text() != null) {
			sql.append(" AND ").append(SEARCH);
			p.addValue("q", query.text());
		}
		if (query.after() != null) {
			sql.append(" AND (a.sort_at, a.id) < (:cursorAt, :cursorId)");
			p.addValue("cursorAt", utc(query.after().sortAt())).addValue("cursorId", query.after().id());
		}
		sql.append(" ORDER BY a.sort_at DESC, a.id DESC LIMIT :limit");

		return jdbc.query(sql.toString(), p, (rs, n) -> new Row(summary(rs), instant(rs, "sort_at")));
	}

	public Optional<DetailRow> findDetail(long id, Instant now) {
		String sql = """
				SELECT a.id, a.title, a.excerpt, a.author, a.canonical_url, a.language, a.published_at,
				       a.publisher_updated_at, a.first_ingested_at, a.last_ingested_at, a.publisher_categories,
				       a.source_id, s.code AS source_code, s.name AS source_name, s.homepage_url,
				""" + CATEGORIES + ", " + IMAGE + " FROM article a " + VISIBLE + " WHERE a.id = :id";
		MapSqlParameterSource p = new MapSqlParameterSource("now", utc(now)).addValue("id", id);
		return jdbc.query(sql, p, (rs, n) -> {
			String language = rs.getString("language");
			ArticleDetail detail = new ArticleDetail(rs.getLong("id"), rs.getString("title"), rs.getString("excerpt"),
					rs.getString("author"), publisher(rs), rs.getString("canonical_url"), language,
					ReaderDtos.direction(language), instant(rs, "published_at"), instant(rs, "publisher_updated_at"),
					instant(rs, "first_ingested_at"), instant(rs, "last_ingested_at"), strings(rs, "categories"),
					strings(rs, "publisher_categories"), FullTextStatus.NOT_PERMITTED, null, false, List.of(),
					rs.getString("image_url"));
			return new DetailRow(rs.getLong("source_id"), detail);
		}).stream().findFirst();
	}

	/** Video links of one article, in feed order: [url, mimeType]. */
	public List<String[]> findVideos(long articleId) {
		return jdbc.query("SELECT source_url, mime_type FROM article_video WHERE article_id = :id ORDER BY position",
				new MapSqlParameterSource("id", articleId),
				(rs, n) -> new String[] { rs.getString("source_url"), rs.getString("mime_type") });
	}

	public List<Publisher> findVisiblePublishers(Instant now) {
		String sql = """
				SELECT s.code AS source_code, s.name AS source_name, s.homepage_url
				FROM news_source s
				JOIN source_rights r ON r.source_id = s.id AND r.content_use = 'METADATA'
				     AND r.status = 'PERMITTED' AND NOT r.suspended
				     AND (r.valid_from IS NULL OR r.valid_from <= :now)
				     AND (r.expires_at IS NULL OR r.expires_at > :now)
				WHERE s.status = 'ACTIVE'
				ORDER BY s.name
				""";
		return jdbc.query(sql, new MapSqlParameterSource("now", utc(now)), (rs, n) -> publisher(rs));
	}

	public List<String> findActiveCategoryCodes() {
		return jdbc.queryForList("SELECT code FROM category WHERE active ORDER BY sort_order",
				new MapSqlParameterSource(), String.class);
	}

	public static ArticleSummary summary(ResultSet rs) throws SQLException {
		String language = rs.getString("language");
		return new ArticleSummary(rs.getLong("id"), rs.getString("title"), rs.getString("excerpt"), publisher(rs),
				rs.getString("canonical_url"), language, ReaderDtos.direction(language), instant(rs, "published_at"),
				instant(rs, "publisher_updated_at"), instant(rs, "first_ingested_at"), strings(rs, "categories"),
				rs.getString("image_url"));
	}

	private static Publisher publisher(ResultSet rs) throws SQLException {
		return new Publisher(rs.getString("source_code"), rs.getString("source_name"), rs.getString("homepage_url"));
	}

	public static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return (value == null) ? null : value.toInstant();
	}

	private static List<String> strings(ResultSet rs, String column) throws SQLException {
		java.sql.Array array = rs.getArray(column);
		return (array == null) ? List.of() : Arrays.asList((String[]) array.getArray());
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	public record Row(ArticleSummary summary, Instant sortAt) {
	}

	public record DetailRow(long sourceId, ArticleDetail detail) {
	}

}
