package org.newsportal.notify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.newsportal.notify.NotificationDtos.Frequency;
import org.newsportal.notify.NotificationMailer.Item;
import org.newsportal.notify.NotificationMailer.Recipient;
import org.newsportal.reader.ReaderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends due digests and breaking-news alerts.
 *
 * Not transactional on purpose: each delivery is recorded (and committed)
 * before its email is sent, so a crash or retry never sends the same email
 * twice. A failed send is recorded as FAILED and not retried.
 */
@Component
public class NotificationSender {

	static final int DAILY_ITEMS = 15;

	static final int WEEKLY_ITEMS = 30;

	private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

	private final NamedParameterJdbcTemplate jdbc;

	private final NotificationMailer mailer;

	private final NotificationProperties props;

	private final Clock clock;

	public NotificationSender(NamedParameterJdbcTemplate jdbc, NotificationMailer mailer, NotificationProperties props,
			Clock clock) {
		this.jdbc = jdbc;
		this.mailer = mailer;
		this.props = props;
		this.clock = clock;
	}

	// --- breaking-news alerts ---

	/** Sends every alert whose hold has ended. Returns the number of emails sent. */
	public int sendDueAlerts() {
		Instant now = clock.instant();
		List<Long> due = jdbc.queryForList("""
				SELECT id FROM breaking_alert WHERE status IN ('QUEUED', 'SENDING') AND send_after <= :now ORDER BY id
				""", new MapSqlParameterSource("now", utc(now)), Long.class);
		int sent = 0;
		for (long id : due) {
			sent += sendAlert(id, now);
		}
		return sent;
	}

	private int sendAlert(long alertId, Instant now) {
		var p = new MapSqlParameterSource("id", alertId).addValue("now", utc(now));
		// Claims the alert; a cancellation that got there first wins.
		if (jdbc.update("UPDATE breaking_alert SET status = 'SENDING' WHERE id = :id AND status IN ('QUEUED', 'SENDING')",
				p) == 0) {
			return 0;
		}
		// Rights are checked again at send time (FR-005).
		List<Item> article = jdbc.query("""
				SELECT a.id, a.title, a.language, s.name AS source_name
				FROM breaking_alert b JOIN article a ON a.id = b.article_id
				""" + ReaderRepository.VISIBLE + " WHERE b.id = :id", p,
				(rs, n) -> new Item(rs.getLong("id"), rs.getString("title"), rs.getString("language"),
						rs.getString("source_name"), List.of()));
		if (article.isEmpty()) {
			jdbc.update("""
					UPDATE breaking_alert SET status = 'CANCELLED', cancelled_at = :now,
					       cancel_reason = 'Not sent: the article is no longer shown to readers.'
					WHERE id = :id
					""", p);
			log.warn("Breaking-news alert {} cancelled: its article is no longer shown to readers", alertId);
			return 0;
		}
		Item item = article.getFirst();
		p.addValue("aid", item.articleId())
			.addValue("lang", item.language())
			.addValue("batch", props.batchSize())
			.addValue("since", utc(now.minus(Duration.ofDays(1))))
			.addValue("max", props.maxAlertsPerReaderPerDay());

		int sent = 0;
		int skipped = 0;
		List<Recipient> batch;
		do {
			batch = jdbc.query("""
					SELECT u.id, u.email, coalesce(u.digest_language, u.ui_language) AS lang, ns.unsubscribe_token
					FROM notification_setting ns JOIN app_user u ON u.id = ns.user_id
					WHERE ns.breaking_alerts AND""" + Audience.ACTIVE_READER + """
					  AND NOT EXISTS (SELECT 1 FROM notification_delivery d WHERE d.alert_id = :id AND d.user_id = u.id)
					  AND""" + Audience.languageMatches(":lang") + " AND " + Audience.topicMatches(":aid") + """
					ORDER BY u.id LIMIT :batch
					""", p, (rs, n) -> new Recipient(rs.getLong("id"), rs.getString("email"), rs.getString("lang"),
							rs.getString("unsubscribe_token")));
			for (Recipient to : batch) {
				var d = new MapSqlParameterSource("uid", to.userId()).addValue("id", alertId)
					.addValue("now", utc(now))
					.addValue("since", p.getValue("since"))
					.addValue("max", props.maxAlertsPerReaderPerDay());
				// FR-067: at most N alerts per reader per 24 hours.
				Boolean full = jdbc.queryForObject("""
						SELECT count(*) >= :max FROM notification_delivery
						WHERE user_id = :uid AND kind = 'ALERT' AND outcome = 'SENT' AND created_at > :since
						""", d, Boolean.class);
				if (Boolean.TRUE.equals(full)) {
					jdbc.update("""
							INSERT INTO notification_delivery (user_id, kind, alert_id, outcome, item_count, created_at)
							VALUES (:uid, 'ALERT', :id, 'SKIPPED_LIMIT', 1, :now)
							""", d);
					skipped++;
					continue;
				}
				if (deliver("ALERT", alertId, to.userId(), 1, now, () -> mailer.sendAlert(to, item))) {
					sent++;
				}
			}
		}
		while (batch.size() == props.batchSize());

		jdbc.update("""
				UPDATE breaking_alert SET status = 'SENT', sent_at = :now,
				       recipient_count = recipient_count + :sent, skipped_count = skipped_count + :skipped
				WHERE id = :id
				""", p.addValue("sent", sent).addValue("skipped", skipped));
		log.info("Breaking-news alert {} sent to {} reader(s); {} skipped by the daily limit", alertId, sent, skipped);
		return sent;
	}

	// --- digests ---

	/** Sends every digest that is due. Returns the number of emails sent. */
	public int sendDueDigests() {
		Instant now = clock.instant();
		var p = new MapSqlParameterSource("now", utc(now)).addValue("batch", props.batchSize());
		int sent = 0;
		List<DueDigest> due;
		do {
			due = jdbc.query("""
					SELECT u.id, u.email, coalesce(u.digest_language, u.ui_language) AS lang, ns.unsubscribe_token,
					       ns.digest_frequency, ns.last_digest_at, ns.next_digest_at
					FROM notification_setting ns JOIN app_user u ON u.id = ns.user_id
					WHERE ns.digest_frequency <> 'OFF' AND ns.next_digest_at <= :now AND""" + Audience.ACTIVE_READER + """
					ORDER BY ns.next_digest_at, u.id LIMIT :batch
					""", p, (rs, n) -> new DueDigest(
					new Recipient(rs.getLong("id"), rs.getString("email"), rs.getString("lang"),
							rs.getString("unsubscribe_token")),
					Frequency.valueOf(rs.getString("digest_frequency")), ReaderRepository.instant(rs, "last_digest_at"),
					rs.getObject("next_digest_at", OffsetDateTime.class)));
			for (DueDigest digest : due) {
				sent += sendDigest(digest, now) ? 1 : 0;
			}
		}
		while (due.size() == props.batchSize());
		return sent;
	}

	private boolean sendDigest(DueDigest digest, Instant now) {
		Duration period = Audience.period(digest.frequency());
		Instant since = (digest.lastAt() == null || digest.lastAt().isBefore(now.minus(period))) ? now.minus(period)
				: digest.lastAt();
		var p = new MapSqlParameterSource("uid", digest.to().userId()).addValue("now", utc(now))
			.addValue("since", utc(since));
		// Articles that arrived since the last digest; publisher time is not used
		// because it can be old or in the future.
		List<Item> found = jdbc.query("""
				SELECT a.id, a.title, a.language, s.name AS source_name FROM article a
				""" + ReaderRepository.VISIBLE + """
				JOIN app_user u ON u.id = :uid
				WHERE a.first_ingested_at > :since AND a.first_ingested_at <= :now
				  AND""" + Audience.languageMatches("a.language") + " AND " + Audience.topicMatches("a.id") + """
				ORDER BY a.sort_at DESC, a.id DESC LIMIT 300
				""", p, (rs, n) -> new Item(rs.getLong("id"), rs.getString("title"), rs.getString("language"),
						rs.getString("source_name"), List.of()));
		List<Item> items = mergeRepeats(found,
				digest.frequency() == Frequency.DAILY ? DAILY_ITEMS : WEEKLY_ITEMS);

		Instant next = digest.next().toInstant().plus(period);
		if (!next.isAfter(now)) {
			next = now.plus(period);
		}
		// Moves the schedule first. Skips the reader if their settings changed meanwhile.
		int claimed = jdbc.update("""
				UPDATE notification_setting SET last_digest_at = :now, next_digest_at = :next
				WHERE user_id = :uid AND next_digest_at = :oldNext AND digest_frequency = :frequency
				""", p.addValue("next", utc(next)).addValue("oldNext", digest.next())
			.addValue("frequency", digest.frequency().name()));
		if (claimed == 0 || items.isEmpty()) {
			// No email when nothing new matched.
			return false;
		}
		return deliver("DIGEST", null, digest.to().userId(), items.size(), now,
				() -> mailer.sendDigest(digest.to(), digest.frequency(), items));
	}

	/**
	 * FR-067: the same headline from several publishers (usually syndication)
	 * is listed once, naming the other publishers. Keeps the first {@code max}
	 * stories.
	 */
	static List<Item> mergeRepeats(List<Item> items, int max) {
		Map<String, Item> first = new LinkedHashMap<>();
		Map<String, List<String>> others = new LinkedHashMap<>();
		for (Item item : items) {
			String key = Audience.titleKey(item.title());
			Item kept = first.get(key);
			if (kept == null) {
				if (first.size() < max) {
					first.put(key, item);
					others.put(key, new ArrayList<>());
				}
				continue;
			}
			List<String> names = others.get(key);
			if (!item.sourceName().equals(kept.sourceName()) && !names.contains(item.sourceName())) {
				names.add(item.sourceName());
			}
		}
		return first.entrySet().stream()
			.map(e -> new Item(e.getValue().articleId(), e.getValue().title(), e.getValue().language(),
					e.getValue().sourceName(), List.copyOf(others.get(e.getKey()))))
			.toList();
	}

	// --- shared ---

	/** Records the delivery, then sends. Returns true if the email was handed to the mail server. */
	private boolean deliver(String kind, Long alertId, long userId, int itemCount, Instant now, Runnable send) {
		Long id = jdbc.queryForObject("""
				INSERT INTO notification_delivery (user_id, kind, alert_id, outcome, item_count, created_at)
				VALUES (:uid, :kind, :aid, 'SENT', :items, :now) RETURNING id
				""", new MapSqlParameterSource("uid", userId).addValue("kind", kind)
			.addValue("aid", alertId)
			.addValue("items", itemCount)
			.addValue("now", utc(now)), Long.class);
		try {
			send.run();
			return true;
		}
		catch (RuntimeException ex) {
			jdbc.update("UPDATE notification_delivery SET outcome = 'FAILED' WHERE id = :id",
					new MapSqlParameterSource("id", id));
			// No address or content in the log.
			log.error("Could not send {} email (delivery {}): {}", kind, id, ex.getClass().getSimpleName());
			return false;
		}
	}

	/** Deletes delivery records older than the retention period. */
	public int purgeOldDeliveries() {
		return jdbc.update("DELETE FROM notification_delivery WHERE created_at < :cutoff",
				new MapSqlParameterSource("cutoff", utc(clock.instant().minus(props.deliveryRetention()))));
	}

	private record DueDigest(Recipient to, Frequency frequency, Instant lastAt, OffsetDateTime next) {
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
