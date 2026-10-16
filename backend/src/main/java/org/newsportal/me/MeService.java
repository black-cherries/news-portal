package org.newsportal.me;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.newsportal.me.MeDtos.CollectionView;
import org.newsportal.me.MeDtos.HistoryItem;
import org.newsportal.me.MeDtos.HistoryKind;
import org.newsportal.me.MeDtos.HistoryPage;
import org.newsportal.me.MeDtos.Preferences;
import org.newsportal.me.MeDtos.SavedItem;
import org.newsportal.me.MeDtos.SavedPage;
import org.newsportal.reader.ArticleQuery;
import org.newsportal.reader.Cursor;
import org.newsportal.reader.ReaderDtos.ArticlePage;
import org.newsportal.reader.ReaderRepository;
import org.newsportal.reader.ReaderService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A reader's preferences, For You feed, bookmarks, collections and history.
 * Every query is scoped to the signed-in user's id; another user's items
 * answer 404, never 403, so their existence is not revealed (FR-035). Saved
 * lists only show articles the publisher's rights still allow (FR-036).
 */
@Service
@Transactional
public class MeService {

	static final Set<String> LANGUAGES = Set.of("en", "hi", "ur", "ar");

	static final int MAX_BOOKMARKS = 5000;

	static final int MAX_COLLECTIONS = 100;

	static final int MAX_COLLECTION_ITEMS = 1000;

	static final int MAX_PAGE_SIZE = 50;

	private static final String ARTICLE_COLUMNS = """
			a.id, a.title, a.excerpt, a.canonical_url, a.language, a.published_at, a.publisher_updated_at,
			a.first_ingested_at, s.code AS source_code, s.name AS source_name, s.homepage_url,
			""" + ReaderRepository.CATEGORIES;

	private final NamedParameterJdbcTemplate jdbc;

	private final ReaderService reader;

	private final Clock clock;

	public MeService(NamedParameterJdbcTemplate jdbc, ReaderService reader, Clock clock) {
		this.jdbc = jdbc;
		this.reader = reader;
		this.clock = clock;
	}

	// --- preferences (FR-045, FR-046) ---

	@Transactional(readOnly = true)
	public Preferences preferences(long userId) {
		var p = params(userId);
		return jdbc.queryForObject("SELECT ui_language, digest_language, history_paused FROM app_user WHERE id = :uid",
				p, (rs, n) -> new Preferences(rs.getString("ui_language"),
						jdbc.queryForList("SELECT language FROM user_language WHERE user_id = :uid ORDER BY position",
								p, String.class),
						jdbc.queryForList("""
								SELECT c.code FROM user_topic t JOIN category c ON c.id = t.category_id
								WHERE t.user_id = :uid ORDER BY c.sort_order
								""", p, String.class),
						rs.getString("digest_language"), rs.getBoolean("history_paused")));
	}

	public Preferences updatePreferences(long userId, Preferences prefs) {
		if (prefs.uiLanguage() == null || !LANGUAGES.contains(prefs.uiLanguage())) {
			throw new IllegalArgumentException("uiLanguage must be one of " + LANGUAGES);
		}
		if (prefs.digestLanguage() != null && !LANGUAGES.contains(prefs.digestLanguage())) {
			throw new IllegalArgumentException("digestLanguage must be one of " + LANGUAGES);
		}
		List<String> languages = distinct(prefs.newsLanguages());
		for (String language : languages) {
			if (!LANGUAGES.contains(language)) {
				throw new IllegalArgumentException("newsLanguages must be from " + LANGUAGES);
			}
		}
		List<String> topics = distinct(prefs.topics());
		var p = params(userId);
		if (!topics.isEmpty()) {
			Integer known = jdbc.queryForObject("SELECT count(*) FROM category WHERE active AND code IN (:codes)",
					new MapSqlParameterSource("codes", topics), Integer.class);
			if (known == null || known != topics.size()) {
				throw new IllegalArgumentException("unknown topic");
			}
		}

		jdbc.update("""
				UPDATE app_user SET ui_language = :ui, digest_language = :digest, history_paused = :paused
				WHERE id = :uid
				""", p.addValue("ui", prefs.uiLanguage())
			.addValue("digest", prefs.digestLanguage())
			.addValue("paused", Boolean.TRUE.equals(prefs.historyPaused())));
		jdbc.update("DELETE FROM user_language WHERE user_id = :uid", p);
		for (int i = 0; i < languages.size(); i++) {
			jdbc.update("INSERT INTO user_language (user_id, language, position) VALUES (:uid, :lang, :pos)",
					params(userId).addValue("lang", languages.get(i)).addValue("pos", i));
		}
		jdbc.update("DELETE FROM user_topic WHERE user_id = :uid", p);
		if (!topics.isEmpty()) {
			jdbc.update("""
					INSERT INTO user_topic (user_id, category_id)
					SELECT :uid, id FROM category WHERE code IN (:codes)
					""", params(userId).addValue("codes", topics));
		}
		return preferences(userId);
	}

	// --- For You (FR-024, FR-029) ---

	/** Rule-based: the reader's news languages (or interface language) and chosen topics. Newest first. */
	@Transactional(readOnly = true)
	public ArticlePage forYou(long userId, String cursor, int size) {
		Preferences prefs = preferences(userId);
		return reader.find(new ArticleQuery(feedLanguages(prefs), List.of(), prefs.topics(), List.of(), null, null,
				null, Cursor.decode(cursor), size));
	}

	/** A small, separately labelled sample from languages the reader did not choose. */
	@Transactional(readOnly = true)
	public ArticlePage otherLanguages(long userId, int size) {
		Preferences prefs = preferences(userId);
		int n = (size <= 0) ? 5 : Math.min(size, 10);
		return reader.find(new ArticleQuery(List.of(), feedLanguages(prefs), prefs.topics(), List.of(), null, null,
				null, null, n));
	}

	private static List<String> feedLanguages(Preferences prefs) {
		return prefs.newsLanguages().isEmpty() ? List.of(prefs.uiLanguage()) : prefs.newsLanguages();
	}

	// --- bookmarks (FR-034) ---

	public void addBookmark(long userId, long articleId) {
		requireVisible(articleId);
		var p = params(userId).addValue("aid", articleId).addValue("now", utc(clock.instant()));
		Integer count = jdbc.queryForObject("SELECT count(*) FROM bookmark WHERE user_id = :uid", p, Integer.class);
		if (count != null && count >= MAX_BOOKMARKS) {
			throw new IllegalStateException("bookmark limit of " + MAX_BOOKMARKS + " reached");
		}
		jdbc.update("""
				INSERT INTO bookmark (user_id, article_id, created_at) VALUES (:uid, :aid, :now)
				ON CONFLICT (user_id, article_id) DO NOTHING
				""", p);
	}

	@Transactional(readOnly = true)
	public boolean isBookmarked(long userId, long articleId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM bookmark WHERE user_id = :uid AND article_id = :aid)",
				params(userId).addValue("aid", articleId), Boolean.class));
	}

	public void removeBookmark(long userId, long articleId) {
		jdbc.update("DELETE FROM bookmark WHERE user_id = :uid AND article_id = :aid",
				params(userId).addValue("aid", articleId));
	}

	@Transactional(readOnly = true)
	public SavedPage bookmarks(long userId, String text, int page, int size) {
		return savedPage("bookmark x", "x.user_id = :uid", "x.created_at", params(userId), text, page, size);
	}

	// --- collections (FR-034) ---

	@Transactional(readOnly = true)
	public List<CollectionView> collections(long userId) {
		return jdbc.query("""
				SELECT c.id, c.name, c.created_at, c.updated_at,
				       (SELECT count(*) FROM collection_item i WHERE i.collection_id = c.id) AS item_count
				FROM collection c WHERE c.user_id = :uid ORDER BY lower(c.name)
				""", params(userId), (rs, n) -> collection(rs));
	}

	public CollectionView createCollection(long userId, String rawName) {
		String name = collectionName(rawName);
		var p = params(userId);
		Integer count = jdbc.queryForObject("SELECT count(*) FROM collection WHERE user_id = :uid", p, Integer.class);
		if (count != null && count >= MAX_COLLECTIONS) {
			throw new IllegalStateException("collection limit of " + MAX_COLLECTIONS + " reached");
		}
		OffsetDateTime now = utc(clock.instant());
		try {
			Long id = jdbc.queryForObject("""
					INSERT INTO collection (user_id, name, created_at, updated_at) VALUES (:uid, :name, :now, :now)
					RETURNING id
					""", p.addValue("name", name).addValue("now", now), Long.class);
			return collection(userId, id);
		}
		catch (DuplicateKeyException ex) {
			throw new IllegalStateException("you already have a collection with this name");
		}
	}

	public CollectionView renameCollection(long userId, long collectionId, String rawName) {
		requireOwnCollection(userId, collectionId);
		String name = collectionName(rawName);
		try {
			jdbc.update("UPDATE collection SET name = :name, updated_at = :now WHERE id = :cid AND user_id = :uid",
					params(userId).addValue("cid", collectionId).addValue("name", name)
						.addValue("now", utc(clock.instant())));
		}
		catch (DuplicateKeyException ex) {
			throw new IllegalStateException("you already have a collection with this name");
		}
		return collection(userId, collectionId);
	}

	public void deleteCollection(long userId, long collectionId) {
		requireOwnCollection(userId, collectionId);
		jdbc.update("DELETE FROM collection WHERE id = :cid AND user_id = :uid",
				params(userId).addValue("cid", collectionId));
	}

	public void addToCollection(long userId, long collectionId, long articleId) {
		requireOwnCollection(userId, collectionId);
		requireVisible(articleId);
		var p = params(userId).addValue("cid", collectionId).addValue("aid", articleId)
			.addValue("now", utc(clock.instant()));
		Integer count = jdbc.queryForObject("SELECT count(*) FROM collection_item WHERE collection_id = :cid", p,
				Integer.class);
		if (count != null && count >= MAX_COLLECTION_ITEMS) {
			throw new IllegalStateException("collection item limit of " + MAX_COLLECTION_ITEMS + " reached");
		}
		int added = jdbc.update("""
				INSERT INTO collection_item (collection_id, article_id, added_at) VALUES (:cid, :aid, :now)
				ON CONFLICT (collection_id, article_id) DO NOTHING
				""", p);
		if (added > 0) {
			jdbc.update("UPDATE collection SET updated_at = :now WHERE id = :cid", p);
		}
	}

	public void removeFromCollection(long userId, long collectionId, long articleId) {
		requireOwnCollection(userId, collectionId);
		jdbc.update("DELETE FROM collection_item WHERE collection_id = :cid AND article_id = :aid",
				params(userId).addValue("cid", collectionId).addValue("aid", articleId));
	}

	@Transactional(readOnly = true)
	public SavedPage collectionItems(long userId, long collectionId, String text, int page, int size) {
		requireOwnCollection(userId, collectionId);
		return savedPage("collection_item x", "x.collection_id = :cid", "x.added_at",
				params(userId).addValue("cid", collectionId), text, page, size);
	}

	// --- history (FR-047, FR-048) ---

	/** Records that the reader opened or listened to an article. Does nothing while history is paused. */
	public boolean recordHistory(long userId, long articleId, HistoryKind kind) {
		if (kind == null) {
			throw new IllegalArgumentException("kind must be READ or LISTEN");
		}
		if (historyPaused(userId)) {
			return false;
		}
		requireVisible(articleId);
		jdbc.update("""
				INSERT INTO reading_history (user_id, article_id, kind, last_at) VALUES (:uid, :aid, :kind, :now)
				ON CONFLICT (user_id, article_id, kind) DO UPDATE SET last_at = EXCLUDED.last_at
				""", params(userId).addValue("aid", articleId).addValue("kind", kind.name())
			.addValue("now", utc(clock.instant())));
		return true;
	}

	@Transactional(readOnly = true)
	public HistoryPage history(long userId, HistoryKind kind, int page, int size) {
		int pageSize = pageSize(size);
		int pageNo = Math.max(page, 0);
		var p = params(userId).addValue("now", utc(clock.instant()))
			.addValue("limit", pageSize + 1)
			.addValue("offset", pageNo * pageSize);
		String kindFilter = "";
		if (kind != null) {
			kindFilter = " AND h.kind = :kind";
			p.addValue("kind", kind.name());
		}
		List<HistoryItem> rows = jdbc.query("SELECT " + ARTICLE_COLUMNS + ", h.kind, h.last_at "
				+ "FROM reading_history h JOIN article a ON a.id = h.article_id " + ReaderRepository.VISIBLE
				+ " WHERE h.user_id = :uid" + kindFilter
				+ " ORDER BY h.last_at DESC, a.id DESC LIMIT :limit OFFSET :offset",
				p, (rs, n) -> new HistoryItem(HistoryKind.valueOf(rs.getString("kind")),
						ReaderRepository.instant(rs, "last_at"), ReaderRepository.summary(rs)));
		boolean more = rows.size() > pageSize;
		return new HistoryPage(more ? rows.subList(0, pageSize) : rows, pageNo, pageSize, more,
				historyPaused(userId));
	}

	public void deleteHistoryItem(long userId, long articleId, HistoryKind kind) {
		var p = params(userId).addValue("aid", articleId);
		String kindFilter = "";
		if (kind != null) {
			kindFilter = " AND kind = :kind";
			p.addValue("kind", kind.name());
		}
		jdbc.update("DELETE FROM reading_history WHERE user_id = :uid AND article_id = :aid" + kindFilter, p);
	}

	public int clearHistory(long userId, HistoryKind kind) {
		var p = params(userId);
		String kindFilter = "";
		if (kind != null) {
			kindFilter = " AND kind = :kind";
			p.addValue("kind", kind.name());
		}
		return jdbc.update("DELETE FROM reading_history WHERE user_id = :uid" + kindFilter, p);
	}

	// --- helpers ---

	/** One page of saved articles from {@code table} (aliased x) with a saved-at column. */
	private SavedPage savedPage(String table, String ownerFilter, String savedAtColumn, MapSqlParameterSource p,
			String text, int page, int size) {
		int pageSize = pageSize(size);
		int pageNo = Math.max(page, 0);
		p.addValue("now", utc(clock.instant())).addValue("limit", pageSize + 1).addValue("offset", pageNo * pageSize);
		String search = "";
		if (text != null && !text.isBlank()) {
			if (text.length() > ArticleQuery.MAX_TEXT) {
				throw new IllegalArgumentException("q must be at most " + ArticleQuery.MAX_TEXT + " characters");
			}
			search = " AND " + ReaderRepository.SEARCH;
			p.addValue("q", text.strip());
		}
		String from = " FROM " + table + " JOIN article a ON a.id = x.article_id " + ReaderRepository.VISIBLE
				+ " WHERE " + ownerFilter;
		List<SavedItem> rows = jdbc.query("SELECT " + ARTICLE_COLUMNS + ", " + savedAtColumn + " AS saved_at" + from
				+ search + " ORDER BY " + savedAtColumn + " DESC, a.id DESC LIMIT :limit OFFSET :offset", p,
				(rs, n) -> new SavedItem(ReaderRepository.instant(rs, "saved_at"), ReaderRepository.summary(rs)));
		Integer total = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + ownerFilter, p,
				Integer.class);
		Integer visible = jdbc.queryForObject("SELECT count(*)" + from, p, Integer.class);
		boolean more = rows.size() > pageSize;
		int hidden = Math.max(0, (total == null ? 0 : total) - (visible == null ? 0 : visible));
		return new SavedPage(more ? rows.subList(0, pageSize) : rows, pageNo, pageSize, more, hidden);
	}

	private void requireVisible(long articleId) {
		Boolean visible = jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM article a " + ReaderRepository.VISIBLE + " WHERE a.id = :aid)",
				new MapSqlParameterSource("aid", articleId).addValue("now", utc(clock.instant())), Boolean.class);
		if (!Boolean.TRUE.equals(visible)) {
			throw new NoSuchElementException("article not found");
		}
	}

	private void requireOwnCollection(long userId, long collectionId) {
		Boolean own = jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM collection WHERE id = :cid AND user_id = :uid)",
				params(userId).addValue("cid", collectionId), Boolean.class);
		if (!Boolean.TRUE.equals(own)) {
			throw new NoSuchElementException("collection not found");
		}
	}

	private boolean historyPaused(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("SELECT history_paused FROM app_user WHERE id = :uid",
				params(userId), Boolean.class));
	}

	private CollectionView collection(long userId, long collectionId) {
		return jdbc.queryForObject("""
				SELECT c.id, c.name, c.created_at, c.updated_at,
				       (SELECT count(*) FROM collection_item i WHERE i.collection_id = c.id) AS item_count
				FROM collection c WHERE c.id = :cid AND c.user_id = :uid
				""", params(userId).addValue("cid", collectionId), (rs, n) -> collection(rs));
	}

	private static CollectionView collection(ResultSet rs) throws SQLException {
		return new CollectionView(rs.getLong("id"), rs.getString("name"), rs.getInt("item_count"),
				ReaderRepository.instant(rs, "created_at"), ReaderRepository.instant(rs, "updated_at"));
	}

	private static String collectionName(String raw) {
		String name = (raw == null) ? "" : raw.strip();
		if (name.isEmpty() || name.codePointCount(0, name.length()) > 100) {
			throw new IllegalArgumentException("name must be 1 to 100 characters");
		}
		return name;
	}

	private static int pageSize(int size) {
		if (size == 0) {
			return 20;
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
		return size;
	}

	private static List<String> distinct(List<String> values) {
		if (values == null) {
			return List.of();
		}
		List<String> out = new ArrayList<>(new LinkedHashSet<>(values));
		if (out.size() > 20) {
			throw new IllegalArgumentException("too many values");
		}
		return out;
	}

	private static MapSqlParameterSource params(long userId) {
		return new MapSqlParameterSource("uid", userId);
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
