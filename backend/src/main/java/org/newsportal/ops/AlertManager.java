package org.newsportal.ops;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.newsportal.mail.MailGateway;
import org.newsportal.mail.MailGateway.OutgoingMail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns the current set of problems into email alerts (FR-078).
 *
 * - A new problem is emailed at once.
 * - A problem that continues is re-sent only after the cooldown.
 * - A problem that disappears is emailed once as resolved.
 * State is stored in ops_alert, so a restart neither forgets nor repeats alerts.
 * All changes from one check go out in a single email.
 */
@Component
public class AlertManager {

	private static final Logger log = LoggerFactory.getLogger(AlertManager.class);

	private final JdbcTemplate jdbc;

	private final MailGateway mail;

	private final OpsProperties props;

	private final Clock clock;

	public AlertManager(JdbcTemplate jdbc, MailGateway mail, OpsProperties props, Clock clock) {
		this.jdbc = jdbc;
		this.mail = mail;
		this.props = props;
		this.clock = clock;
	}

	public record Change(String key, String message, String kind) {
	}

	/** @param active every problem present right now, keyed by a stable id */
	public List<Change> evaluate(Map<String, String> active) {
		Instant now = clock.instant();
		OffsetDateTime nowUtc = now.atOffset(ZoneOffset.UTC);
		List<Change> changes = new ArrayList<>();

		for (Map.Entry<String, String> problem : active.entrySet()) {
			String key = problem.getKey();
			String message = truncate(problem.getValue());
			List<Map<String, Object>> rows = jdbc.queryForList(
					"SELECT status, last_notified_at FROM ops_alert WHERE alert_key = ?", key);
			if (rows.isEmpty() || "RESOLVED".equals(rows.getFirst().get("status"))) {
				jdbc.update("""
						INSERT INTO ops_alert (alert_key, status, message, first_seen_at, last_seen_at, last_notified_at)
						VALUES (?, 'OPEN', ?, ?, ?, ?)
						ON CONFLICT (alert_key) DO UPDATE SET status = 'OPEN', message = EXCLUDED.message,
						    first_seen_at = EXCLUDED.first_seen_at, last_seen_at = EXCLUDED.last_seen_at,
						    last_notified_at = EXCLUDED.last_notified_at, resolved_at = NULL
						""", key, message, nowUtc, nowUtc, nowUtc);
				changes.add(new Change(key, message, "NEW"));
				continue;
			}
			OffsetDateTime lastNotified = (OffsetDateTime) toOffset(rows.getFirst().get("last_notified_at"));
			boolean remind = lastNotified == null
					|| !lastNotified.toInstant().plus(props.monitor().alertCooldown()).isAfter(now);
			jdbc.update("UPDATE ops_alert SET message = ?, last_seen_at = ?" + (remind ? ", last_notified_at = ?" : "")
					+ " WHERE alert_key = ?", remind ? new Object[] { message, nowUtc, nowUtc, key }
							: new Object[] { message, nowUtc, key });
			if (remind) {
				changes.add(new Change(key, message, "STILL_OPEN"));
			}
		}

		for (Map<String, Object> open : jdbc.queryForList("SELECT alert_key, message FROM ops_alert WHERE status = 'OPEN'")) {
			String key = (String) open.get("alert_key");
			if (!active.containsKey(key)) {
				jdbc.update("UPDATE ops_alert SET status = 'RESOLVED', resolved_at = ? WHERE alert_key = ?", nowUtc, key);
				changes.add(new Change(key, (String) open.get("message"), "RESOLVED"));
			}
		}

		if (!changes.isEmpty()) {
			notify(changes);
		}
		return changes;
	}

	/** Used when the database itself is unreachable, so no state can be stored. */
	void sendDirect(String subject, String body) {
		for (String to : recipients(false)) {
			try {
				mail.send(new OutgoingMail(to, subject, body));
			}
			catch (RuntimeException ex) {
				log.error("Could not send alert email: {}", ex.getClass().getSimpleName());
			}
		}
	}

	private void notify(List<Change> changes) {
		long problems = changes.stream().filter(c -> !"RESOLVED".equals(c.kind())).count();
		long resolved = changes.size() - problems;
		String subject = "[News Portal] " + (problems > 0 ? problems + " problem(s)" : "")
				+ (problems > 0 && resolved > 0 ? ", " : "") + (resolved > 0 ? resolved + " resolved" : "");
		StringBuilder body = new StringBuilder("Operational status changes at ").append(clock.instant()).append(" UTC\n\n");
		for (Change c : changes) {
			String label = switch (c.kind()) {
				case "NEW" -> "NEW";
				case "STILL_OPEN" -> "STILL OPEN";
				default -> "RESOLVED";
			};
			body.append("[").append(label).append("] ").append(c.message()).append("\n");
		}
		body.append("\nSee GET /api/v1/admin/ops for details. Reminders are sent every ")
			.append(props.monitor().alertCooldown().toHours())
			.append(" hours while a problem continues.\n");
		log.warn("{}", subject);
		for (String to : recipients(true)) {
			try {
				mail.send(new OutgoingMail(to, subject, body.toString()));
			}
			catch (RuntimeException ex) {
				log.error("Could not send alert email: {}", ex.getClass().getSimpleName());
			}
		}
	}

	private List<String> recipients(boolean databaseAvailable) {
		List<String> configured = props.monitor().alertRecipients() == null ? List.of()
				: props.monitor().alertRecipients().stream().filter(s -> s != null && !s.isBlank()).toList();
		if (!configured.isEmpty() || !databaseAvailable) {
			return configured;
		}
		return jdbc.queryForList("""
				SELECT u.email FROM app_user u
				JOIN user_role ur ON ur.user_id = u.id JOIN role r ON r.id = ur.role_id
				WHERE r.code = 'SYSTEM_ADMIN' AND u.status = 'ACTIVE'
				ORDER BY u.email
				""", String.class);
	}

	private static Object toOffset(Object value) {
		if (value instanceof java.sql.Timestamp ts) {
			return ts.toInstant().atOffset(ZoneOffset.UTC);
		}
		return value;
	}

	private static String truncate(String s) {
		return s.length() <= 500 ? s : s.substring(0, 499) + "…";
	}

}
