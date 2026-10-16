package org.newsportal.report;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.newsportal.identity.AuditLog;
import org.newsportal.identity.AuthPrincipal;
import org.newsportal.identity.RateLimiter;
import org.newsportal.ingestion.UrlCanonicalizer;
import org.newsportal.mail.AccountMailer;
import org.newsportal.reader.ReaderRepository;
import org.newsportal.report.ReportDtos.MyReport;
import org.newsportal.report.ReportDtos.PublicUpdate;
import org.newsportal.report.ReportDtos.Resolution;
import org.newsportal.report.ReportDtos.StaffPage;
import org.newsportal.report.ReportDtos.StaffReport;
import org.newsportal.report.ReportDtos.StaffUpdate;
import org.newsportal.report.ReportDtos.Status;
import org.newsportal.report.ReportDtos.StatusRequest;
import org.newsportal.report.ReportDtos.SubmitRequest;
import org.newsportal.report.ReportDtos.Submitted;
import org.newsportal.report.ReportDtos.Type;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User reports and correction requests (FR-070..FR-073).
 *
 * Reports are records for staff to act on. Nothing here changes an article
 * or its publisher's content (FR-073).
 */
@Service
@Transactional
public class ReportService {

	static final int MIN_DESCRIPTION = 10;

	static final int MAX_DESCRIPTION = 2000;

	private static final String REFERENCE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Set<String> UI_LANGUAGES = Set.of("en", "hi", "ur", "ar");

	private final NamedParameterJdbcTemplate jdbc;

	private final RateLimiter limiter;

	private final AccountMailer mailer;

	private final AuditLog audit;

	private final Clock clock;

	public ReportService(NamedParameterJdbcTemplate jdbc, RateLimiter limiter, AccountMailer mailer, AuditLog audit,
			Clock clock) {
		this.jdbc = jdbc;
		this.limiter = limiter;
		this.mailer = mailer;
		this.audit = audit;
		this.clock = clock;
	}

	// --- submitting (guests and readers) ---

	/** @param reporter null for guests */
	public Submitted submit(SubmitRequest req, AuthPrincipal reporter, String uiLanguage, String ip) {
		limiter.acquire("report:ip:" + ip, 10, Duration.ofHours(1));
		if (reporter != null) {
			limiter.acquire("report:user:" + reporter.userId(), 20, Duration.ofDays(1));
		}
		if (req.type() == null) {
			throw new IllegalArgumentException("type is required");
		}
		String description = req.description() == null ? "" : req.description().strip();
		int length = description.codePointCount(0, description.length());
		if (length < MIN_DESCRIPTION || length > MAX_DESCRIPTION) {
			throw new IllegalArgumentException(
					"description must be " + MIN_DESCRIPTION + " to " + MAX_DESCRIPTION + " characters");
		}
		String pageUrl = cleanPageUrl(req.pageUrl());
		Instant now = clock.instant();

		Long sourceId = null;
		if (req.articleId() != null) {
			List<Long> found = jdbc.queryForList(
					"SELECT a.source_id FROM article a " + ReaderRepository.VISIBLE + " WHERE a.id = :aid",
					new MapSqlParameterSource("aid", req.articleId()).addValue("now", utc(now)), Long.class);
			if (found.isEmpty()) {
				throw new IllegalArgumentException("article not found");
			}
			sourceId = found.getFirst();
		}
		boolean isReader = reporter != null && !reporter.isStaff();
		boolean notify = isReader && Boolean.TRUE.equals(req.notifyMe());
		// Set.of(...).contains(null) throws, so check for null first.
		String language = (uiLanguage != null && UI_LANGUAGES.contains(uiLanguage)) ? uiLanguage : null;

		for (int attempt = 0; attempt < 5; attempt++) {
			String reference = newReference();
			try {
				Long id = jdbc.queryForObject("""
						INSERT INTO report (reference, type, article_id, source_id, reporter_user_id, notify_reporter,
						                    description, page_url, ui_language, created_at, updated_at)
						VALUES (:ref, :type, :aid, :sid, :uid, :notify, :description, :pageUrl, :lang, :now, :now)
						RETURNING id
						""", new MapSqlParameterSource("ref", reference)
					.addValue("type", req.type().name())
					.addValue("aid", req.articleId())
					.addValue("sid", sourceId)
					.addValue("uid", isReader ? reporter.userId() : null)
					.addValue("notify", notify)
					.addValue("description", description)
					.addValue("pageUrl", pageUrl)
					.addValue("lang", language)
					.addValue("now", utc(now)), Long.class);
				jdbc.update("""
						INSERT INTO report_status_history (report_id, from_status, to_status, changed_at)
						VALUES (:id, NULL, 'RECEIVED', :now)
						""", new MapSqlParameterSource("id", id).addValue("now", utc(now)));
				return new Submitted(reference, Status.RECEIVED);
			}
			catch (DuplicateKeyException collision) {
				// Extremely unlikely; try another reference.
			}
		}
		throw new IllegalStateException("could not allocate a report reference");
	}

	/** The signed-in reader's own reports, newest first, with public updates only. */
	@Transactional(readOnly = true)
	public List<MyReport> myReports(long userId) {
		return jdbc.query("""
				SELECT r.id, r.reference, r.type, r.status, r.article_id, a.title AS article_title, r.description,
				       r.notify_reporter, r.created_at, r.updated_at
				FROM report r LEFT JOIN article a ON a.id = r.article_id
				WHERE r.reporter_user_id = :uid
				ORDER BY r.created_at DESC
				LIMIT 200
				""", new MapSqlParameterSource("uid", userId), (rs, n) -> new MyReport(rs.getString("reference"),
				Type.valueOf(rs.getString("type")), Status.valueOf(rs.getString("status")),
				(Long) rs.getObject("article_id"), rs.getString("article_title"), rs.getString("description"),
				rs.getBoolean("notify_reporter"), instant(rs, "created_at"), instant(rs, "updated_at"),
				publicUpdates(rs.getLong("id"))));
	}

	public void setNotify(long userId, String reference, boolean notify) {
		int updated = jdbc.update(
				"UPDATE report SET notify_reporter = :notify WHERE reference = :ref AND reporter_user_id = :uid",
				new MapSqlParameterSource("notify", notify).addValue("ref", reference).addValue("uid", userId));
		if (updated == 0) {
			throw new NoSuchElementException("report not found");
		}
	}

	// --- staff ---

	@Transactional(readOnly = true)
	public StaffPage list(Status status, Type type, Long assignedTo, int page, int size) {
		int pageSize = (size <= 0) ? 20 : Math.min(size, 100);
		int pageNo = Math.max(page, 0);
		var p = new MapSqlParameterSource("limit", pageSize + 1).addValue("offset", pageNo * pageSize);
		StringBuilder where = new StringBuilder(" WHERE TRUE");
		if (status != null) {
			where.append(" AND r.status = :status");
			p.addValue("status", status.name());
		}
		if (type != null) {
			where.append(" AND r.type = :type");
			p.addValue("type", type.name());
		}
		if (assignedTo != null) {
			where.append(" AND r.assigned_to = :assignee");
			p.addValue("assignee", assignedTo);
		}
		List<StaffReport> rows = jdbc.query(STAFF_SELECT + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset",
				p, (rs, n) -> staffReport(rs, false));
		boolean more = rows.size() > pageSize;
		return new StaffPage(more ? rows.subList(0, pageSize) : rows, pageNo, pageSize, more);
	}

	@Transactional(readOnly = true)
	public StaffReport get(long id) {
		List<StaffReport> rows = jdbc.query(STAFF_SELECT + " WHERE r.id = :id", new MapSqlParameterSource("id", id),
				(rs, n) -> staffReport(rs, true));
		if (rows.isEmpty()) {
			throw new NoSuchElementException("report " + id + " not found");
		}
		return rows.getFirst();
	}

	/** Assigns to a content reviewer (or to the acting reviewer when {@code assigneeId} is null). */
	public StaffReport assign(long id, Long assigneeId, AuthPrincipal actor) {
		long assignee = (assigneeId == null) ? actor.userId() : assigneeId;
		Boolean reviewer = jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id = ur.role_id
				               JOIN app_user u ON u.id = ur.user_id
				               WHERE ur.user_id = :uid AND r.code = 'CONTENT_REVIEWER' AND u.status = 'ACTIVE')
				""", new MapSqlParameterSource("uid", assignee), Boolean.class);
		if (!Boolean.TRUE.equals(reviewer)) {
			throw new IllegalArgumentException("assignee must be an active content reviewer");
		}
		int updated = jdbc.update("UPDATE report SET assigned_to = :uid, updated_at = :now WHERE id = :id",
				new MapSqlParameterSource("uid", assignee).addValue("now", utc(clock.instant())).addValue("id", id));
		if (updated == 0) {
			throw new NoSuchElementException("report " + id + " not found");
		}
		audit.record(actor.userId(), "REPORT_ASSIGNED", "report", id, "assignee=" + assignee);
		return get(id);
	}

	/**
	 * Moves a report between statuses. Resolving needs a resolution. The
	 * reporter is emailed when they asked for updates (FR-072).
	 */
	public StaffReport changeStatus(long id, StatusRequest req, AuthPrincipal actor) {
		if (req.status() == null) {
			throw new IllegalArgumentException("status is required");
		}
		String publicNote = limit(req.publicNote(), 1000);
		String internalNote = limit(req.internalNote(), 2000);
		Map<String, Object> current = jdbc.queryForList("""
				SELECT r.status, r.reference, r.notify_reporter, u.email, u.status AS user_status
				FROM report r LEFT JOIN app_user u ON u.id = r.reporter_user_id
				WHERE r.id = :id FOR UPDATE OF r
				""", new MapSqlParameterSource("id", id)).stream().findFirst()
			.orElseThrow(() -> new NoSuchElementException("report " + id + " not found"));
		Status from = Status.valueOf((String) current.get("status"));
		Status to = req.status();
		if (from == to) {
			throw new IllegalStateException("report is already " + to);
		}
		if (to == Status.RECEIVED) {
			throw new IllegalStateException("a report cannot go back to RECEIVED");
		}
		Resolution resolution = (to == Status.RESOLVED) ? req.resolution() : null;
		if (to == Status.RESOLVED && resolution == null) {
			throw new IllegalArgumentException("resolution is required to resolve a report");
		}

		OffsetDateTime now = utc(clock.instant());
		jdbc.update("""
				UPDATE report SET status = :to, resolution = :resolution, updated_at = :now,
				       resolved_at = CASE WHEN :to = 'RESOLVED' THEN :now ELSE NULL END
				WHERE id = :id
				""", new MapSqlParameterSource("to", to.name())
			.addValue("resolution", resolution == null ? null : resolution.name())
			.addValue("now", now)
			.addValue("id", id));
		jdbc.update("""
				INSERT INTO report_status_history (report_id, from_status, to_status, changed_by, public_note,
				                                   internal_note, changed_at)
				VALUES (:id, :from, :to, :by, :pub, :internal, :now)
				""", new MapSqlParameterSource("id", id).addValue("from", from.name()).addValue("to", to.name())
			.addValue("by", actor.userId()).addValue("pub", publicNote).addValue("internal", internalNote)
			.addValue("now", now));
		audit.record(actor.userId(), "REPORT_STATUS_CHANGED", "report", id, from + "->" + to);

		if (Boolean.TRUE.equals(current.get("notify_reporter")) && current.get("email") != null
				&& "ACTIVE".equals(current.get("user_status"))) {
			mailer.sendReportUpdate((String) current.get("email"), (String) current.get("reference"),
					statusLabel(to), publicNote);
		}
		return get(id);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> stats() {
		return Map.of(
				"byStatus", jdbc.queryForList("SELECT status, count(*) AS count FROM report GROUP BY status ORDER BY status",
						Map.of()),
				"byType", jdbc.queryForList("SELECT type, count(*) AS count FROM report GROUP BY type ORDER BY type",
						Map.of()),
				"openOlderThan7Days", jdbc.queryForObject(
						"SELECT count(*) FROM report WHERE status <> 'RESOLVED' AND created_at < :cutoff",
						new MapSqlParameterSource("cutoff", utc(clock.instant().minus(Duration.ofDays(7)))),
						Integer.class));
	}

	// --- helpers ---

	private static final String STAFF_SELECT = """
			SELECT r.id, r.reference, r.type, r.status, r.resolution, r.article_id, a.title AS article_title,
			       s.code AS source_code, r.reporter_user_id, r.notify_reporter, r.description, r.page_url,
			       r.ui_language, r.assigned_to, au.email AS assigned_email, r.created_at, r.updated_at, r.resolved_at
			FROM report r
			LEFT JOIN article a ON a.id = r.article_id
			LEFT JOIN news_source s ON s.id = r.source_id
			LEFT JOIN app_user au ON au.id = r.assigned_to
			""";

	private StaffReport staffReport(ResultSet rs, boolean withHistory) throws SQLException {
		long id = rs.getLong("id");
		String resolution = rs.getString("resolution");
		return new StaffReport(id, rs.getString("reference"), Type.valueOf(rs.getString("type")),
				Status.valueOf(rs.getString("status")), resolution == null ? null : Resolution.valueOf(resolution),
				(Long) rs.getObject("article_id"), rs.getString("article_title"), rs.getString("source_code"),
				rs.getObject("reporter_user_id") == null ? "GUEST_OR_DELETED" : "READER",
				rs.getBoolean("notify_reporter"), rs.getString("description"), rs.getString("page_url"),
				rs.getString("ui_language"), (Long) rs.getObject("assigned_to"), rs.getString("assigned_email"),
				instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "resolved_at"),
				withHistory ? staffHistory(id) : List.of());
	}

	private List<StaffUpdate> staffHistory(long reportId) {
		return jdbc.query("""
				SELECT h.from_status, h.to_status, u.email, h.public_note, h.internal_note, h.changed_at
				FROM report_status_history h LEFT JOIN app_user u ON u.id = h.changed_by
				WHERE h.report_id = :id ORDER BY h.changed_at, h.id
				""", new MapSqlParameterSource("id", reportId), (rs, n) -> new StaffUpdate(
				rs.getString("from_status") == null ? null : Status.valueOf(rs.getString("from_status")),
				Status.valueOf(rs.getString("to_status")), rs.getString("email"), rs.getString("public_note"),
				rs.getString("internal_note"), instant(rs, "changed_at")));
	}

	private List<PublicUpdate> publicUpdates(long reportId) {
		return jdbc.query("""
				SELECT to_status, public_note, changed_at FROM report_status_history
				WHERE report_id = :id ORDER BY changed_at, id
				""", new MapSqlParameterSource("id", reportId), (rs, n) -> new PublicUpdate(
				Status.valueOf(rs.getString("to_status")), rs.getString("public_note"), instant(rs, "changed_at")));
	}

	/** Absolute http(s) URL or an app path starting with "/"; anything else is dropped. */
	private static String cleanPageUrl(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.strip();
		if (value.length() > 500) {
			throw new IllegalArgumentException("pageUrl is too long");
		}
		if (value.startsWith("/") && !value.startsWith("//")) {
			return value;
		}
		if (UrlCanonicalizer.canonicalize(value).isPresent()) {
			return value;
		}
		throw new IllegalArgumentException("pageUrl must be an http(s) URL or an app path");
	}

	private static String newReference() {
		StringBuilder sb = new StringBuilder("NP-");
		for (int i = 0; i < 8; i++) {
			sb.append(REFERENCE_CHARS.charAt(RANDOM.nextInt(REFERENCE_CHARS.length())));
		}
		return sb.toString();
	}

	private static String statusLabel(Status s) {
		return switch (s) {
			case RECEIVED -> "Received";
			case UNDER_REVIEW -> "Under review";
			case RESOLVED -> "Resolved";
		};
	}

	private static String limit(String s, int max) {
		if (s == null || s.isBlank()) {
			return null;
		}
		String v = s.strip();
		if (v.length() > max) {
			throw new IllegalArgumentException("note is longer than " + max + " characters");
		}
		return v;
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
		return t == null ? null : t.toInstant();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
