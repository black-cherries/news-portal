package org.newsportal.notify;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.newsportal.identity.AuditLog;
import org.newsportal.identity.AuthPrincipal;
import org.newsportal.identity.RateLimiter;
import org.newsportal.notify.NotificationDtos.AlertPage;
import org.newsportal.notify.NotificationDtos.AlertRequest;
import org.newsportal.notify.NotificationDtos.AlertStatus;
import org.newsportal.notify.NotificationDtos.AlertView;
import org.newsportal.notify.NotificationDtos.Frequency;
import org.newsportal.notify.NotificationDtos.Settings;
import org.newsportal.notify.NotificationDtos.UnsubscribeKind;
import org.newsportal.reader.ReaderRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Readers' notification settings, unsubscribe links and the staff side of
 * breaking-news alerts (FR-065..FR-067). Sending is done by
 * {@link NotificationSender}.
 */
@Service
@Transactional
public class NotificationService {

	static final Set<String> LANGUAGES = Set.of("en", "hi", "ur", "ar");

	static final int MAX_PAGE_SIZE = 50;

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final String ALERT_COLUMNS = """
			SELECT b.id, b.article_id, b.status, b.reason, b.created_at, b.send_after, b.sent_at, b.recipient_count,
			       b.skipped_count, b.cancelled_at, b.cancel_reason, a.title AS article_title,
			       a.language AS article_language, s.name AS source_name, cu.email AS created_by_email,
			       xu.email AS cancelled_by_email
			FROM breaking_alert b
			LEFT JOIN article a ON a.id = b.article_id
			LEFT JOIN news_source s ON s.id = a.source_id
			LEFT JOIN app_user cu ON cu.id = b.created_by
			LEFT JOIN app_user xu ON xu.id = b.cancelled_by
			""";

	private final NamedParameterJdbcTemplate jdbc;

	private final NotificationProperties props;

	private final AuditLog audit;

	private final RateLimiter limiter;

	private final Clock clock;

	public NotificationService(NamedParameterJdbcTemplate jdbc, NotificationProperties props, AuditLog audit,
			RateLimiter limiter, Clock clock) {
		this.jdbc = jdbc;
		this.props = props;
		this.audit = audit;
		this.limiter = limiter;
		this.clock = clock;
	}

	// --- reader settings (FR-065, FR-066) ---

	@Transactional(readOnly = true)
	public Settings settings(long userId) {
		var p = params(userId);
		List<String> languages = jdbc.queryForList(
				"SELECT language FROM notification_language WHERE user_id = :uid ORDER BY language", p, String.class);
		List<String> topics = jdbc.queryForList("""
				SELECT c.code FROM notification_topic t JOIN category c ON c.id = t.category_id
				WHERE t.user_id = :uid ORDER BY c.sort_order
				""", p, String.class);
		List<Settings> rows = jdbc.query(
				"SELECT digest_frequency, breaking_alerts, next_digest_at FROM notification_setting WHERE user_id = :uid",
				p, (rs, n) -> new Settings(Frequency.valueOf(rs.getString("digest_frequency")),
						rs.getBoolean("breaking_alerts"), languages, topics,
						ReaderRepository.instant(rs, "next_digest_at")));
		return rows.isEmpty() ? new Settings(Frequency.OFF, false, languages, topics, null) : rows.getFirst();
	}

	public Settings updateSettings(long userId, Settings settings) {
		if (settings.digest() == null) {
			throw new IllegalArgumentException("digest must be OFF, DAILY or WEEKLY");
		}
		List<String> languages = distinct(settings.languages());
		for (String language : languages) {
			if (language == null || !LANGUAGES.contains(language)) {
				throw new IllegalArgumentException("languages must be from " + LANGUAGES);
			}
		}
		List<String> topics = distinct(settings.topics());
		if (!topics.isEmpty()) {
			Integer known = jdbc.queryForObject("SELECT count(*) FROM category WHERE active AND code IN (:codes)",
					new MapSqlParameterSource("codes", topics), Integer.class);
			if (known == null || known != topics.size()) {
				throw new IllegalArgumentException("unknown topic");
			}
		}

		Instant now = clock.instant();
		var p = params(userId);
		List<Object[]> current = jdbc.query(
				"SELECT digest_frequency, next_digest_at FROM notification_setting WHERE user_id = :uid FOR UPDATE", p,
				(rs, n) -> new Object[] { rs.getString("digest_frequency"), rs.getObject("next_digest_at", OffsetDateTime.class) });
		String oldFrequency = current.isEmpty() ? Frequency.OFF.name() : (String) current.getFirst()[0];
		OffsetDateTime next;
		if (settings.digest() == Frequency.OFF) {
			next = null;
		}
		else if (settings.digest().name().equals(oldFrequency)) {
			next = (OffsetDateTime) current.getFirst()[1];
		}
		else {
			// The first digest covers one full period from now.
			next = utc(now.plus(Audience.period(settings.digest())));
		}
		jdbc.update("""
				INSERT INTO notification_setting
				    (user_id, digest_frequency, breaking_alerts, unsubscribe_token, next_digest_at, updated_at)
				VALUES (:uid, :frequency, :alerts, :token, :next, :now)
				ON CONFLICT (user_id) DO UPDATE SET digest_frequency = EXCLUDED.digest_frequency,
				    breaking_alerts = EXCLUDED.breaking_alerts, next_digest_at = EXCLUDED.next_digest_at,
				    updated_at = EXCLUDED.updated_at
				""", params(userId).addValue("frequency", settings.digest().name())
			.addValue("alerts", Boolean.TRUE.equals(settings.breakingAlerts()))
			.addValue("token", newToken())
			.addValue("next", next)
			.addValue("now", utc(now)));
		jdbc.update("DELETE FROM notification_language WHERE user_id = :uid", p);
		for (String language : languages) {
			jdbc.update("INSERT INTO notification_language (user_id, language) VALUES (:uid, :lang)",
					params(userId).addValue("lang", language));
		}
		jdbc.update("DELETE FROM notification_topic WHERE user_id = :uid", p);
		if (!topics.isEmpty()) {
			jdbc.update("""
					INSERT INTO notification_topic (user_id, category_id)
					SELECT :uid, id FROM category WHERE code IN (:codes)
					""", params(userId).addValue("codes", topics));
		}
		return settings(userId);
	}

	/**
	 * The link in every notification email. Works without signing in and can
	 * only turn notifications off.
	 */
	public void unsubscribe(String token, UnsubscribeKind kind, String ip) {
		limiter.acquire("unsubscribe:" + ip, 30, Duration.ofHours(1));
		if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
			throw new NoSuchElementException("this unsubscribe link is not valid");
		}
		String changes = switch (kind == null ? UnsubscribeKind.ALL : kind) {
			case DIGEST -> "digest_frequency = 'OFF', next_digest_at = NULL";
			case ALERTS -> "breaking_alerts = FALSE";
			case ALL -> "digest_frequency = 'OFF', next_digest_at = NULL, breaking_alerts = FALSE";
		};
		int updated = jdbc.update(
				"UPDATE notification_setting SET " + changes + ", updated_at = :now WHERE unsubscribe_token = :token",
				new MapSqlParameterSource("token", token).addValue("now", utc(clock.instant())));
		if (updated == 0) {
			throw new NoSuchElementException("this unsubscribe link is not valid");
		}
	}

	// --- breaking-news alerts, staff side (FR-065, FR-067) ---

	public AlertView createAlert(AlertRequest req, AuthPrincipal staff) {
		if (req.articleId() == null) {
			throw new IllegalArgumentException("articleId is required");
		}
		String reason = requiredText(req.reason(), "reason");
		Instant now = clock.instant();
		var p = new MapSqlParameterSource("aid", req.articleId()).addValue("now", utc(now));
		List<String> titles = jdbc.queryForList("SELECT a.title FROM article a " + ReaderRepository.VISIBLE
				+ " WHERE a.id = :aid", p, String.class);
		if (titles.isEmpty()) {
			throw new NoSuchElementException("article not found, or not currently shown to readers");
		}
		String key = Audience.titleKey(titles.getFirst());

		// One alert decision at a time, so the limits below hold.
		jdbc.getJdbcOperations().execute("LOCK TABLE breaking_alert IN SHARE ROW EXCLUSIVE MODE");
		p.addValue("key", key).addValue("since", utc(now.minus(Duration.ofDays(1))));
		Boolean repeat = jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM breaking_alert WHERE status <> 'CANCELLED'
				               AND (article_id = :aid OR (title_key = :key AND created_at > :since AND created_at <= :now)))
				""", p, Boolean.class);
		if (Boolean.TRUE.equals(repeat)) {
			throw new IllegalStateException("an alert for this story was already sent or is waiting to be sent");
		}
		Integer recent = jdbc.queryForObject(
				"SELECT count(*) FROM breaking_alert WHERE status <> 'CANCELLED' AND created_at > :since AND created_at <= :now", p,
				Integer.class);
		if (recent != null && recent >= props.maxAlertsPerDay()) {
			throw new IllegalStateException(
					"the limit of " + props.maxAlertsPerDay() + " alerts in 24 hours has been reached");
		}
		Long id = jdbc.queryForObject("""
				INSERT INTO breaking_alert (article_id, title_key, status, reason, created_by, created_at, send_after)
				VALUES (:aid, :key, 'QUEUED', :reason, :uid, :now, :sendAfter)
				RETURNING id
				""", p.addValue("reason", reason)
			.addValue("uid", staff.userId())
			.addValue("sendAfter", utc(now.plus(props.alertHold()))), Long.class);
		audit.record(staff.userId(), "ALERT_CREATED", "breaking_alert", id, "article " + req.articleId() + ": " + reason);
		return alert(id);
	}

	public AlertView cancelAlert(long id, String rawReason, AuthPrincipal staff) {
		String reason = requiredText(rawReason, "reason");
		int updated = jdbc.update("""
				UPDATE breaking_alert SET status = 'CANCELLED', cancelled_by = :uid, cancelled_at = :now,
				       cancel_reason = :reason
				WHERE id = :id AND status = 'QUEUED'
				""", new MapSqlParameterSource("id", id).addValue("uid", staff.userId())
			.addValue("now", utc(clock.instant()))
			.addValue("reason", reason));
		if (updated == 0) {
			AlertView existing = alert(id);
			throw new IllegalStateException("only queued alerts can be cancelled; this one is " + existing.status());
		}
		audit.record(staff.userId(), "ALERT_CANCELLED", "breaking_alert", id, reason);
		return alert(id);
	}

	@Transactional(readOnly = true)
	public AlertPage alerts(int page, int size) {
		int pageSize = (size == 0) ? 20 : size;
		if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
		int pageNo = Math.max(page, 0);
		List<AlertView> rows = jdbc.query(ALERT_COLUMNS + " ORDER BY b.created_at DESC, b.id DESC LIMIT :limit OFFSET :offset",
				new MapSqlParameterSource("limit", pageSize + 1).addValue("offset", pageNo * pageSize),
				(rs, n) -> alert(rs));
		boolean more = rows.size() > pageSize;
		return new AlertPage(more ? rows.subList(0, pageSize) : rows, pageNo, pageSize, more);
	}

	@Transactional(readOnly = true)
	public AlertView alert(long id) {
		return jdbc.query(ALERT_COLUMNS + " WHERE b.id = :id", new MapSqlParameterSource("id", id), (rs, n) -> alert(rs))
			.stream()
			.findFirst()
			.orElseThrow(() -> new NoSuchElementException("alert not found"));
	}

	// --- helpers ---

	private static AlertView alert(ResultSet rs) throws SQLException {
		long articleId = rs.getLong("article_id");
		return new AlertView(rs.getLong("id"), rs.wasNull() ? null : articleId, rs.getString("article_title"),
				rs.getString("article_language"), rs.getString("source_name"),
				AlertStatus.valueOf(rs.getString("status")), rs.getString("reason"), rs.getString("created_by_email"),
				ReaderRepository.instant(rs, "created_at"), ReaderRepository.instant(rs, "send_after"),
				ReaderRepository.instant(rs, "sent_at"), rs.getInt("recipient_count"), rs.getInt("skipped_count"),
				rs.getString("cancelled_by_email"), ReaderRepository.instant(rs, "cancelled_at"),
				rs.getString("cancel_reason"));
	}

	private static String requiredText(String value, String name) {
		String text = (value == null) ? "" : value.strip();
		if (text.isEmpty() || text.length() > 500) {
			throw new IllegalArgumentException(name + " must be 1 to 500 characters");
		}
		return text;
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

	/** 256 bits, URL-safe; 43 characters. */
	private static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static MapSqlParameterSource params(long userId) {
		return new MapSqlParameterSource("uid", userId);
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
