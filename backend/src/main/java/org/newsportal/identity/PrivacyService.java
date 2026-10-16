package org.newsportal.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.newsportal.identity.AppUser.AccountType;
import org.newsportal.identity.AppUser.Status;
import org.newsportal.mail.AccountMailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Account deletion (FR-052..FR-055).
 *
 * 1. The reader asks to delete: access stops at once and every session ends.
 * 2. During the recovery period they can restore the account by signing in.
 * 3. Afterwards the account and everything it owns is deleted. If other
 *    records still point at it, it is anonymized instead and its own data is
 *    removed. The id goes into the deletion ledger.
 * 4. At startup any ledger id that exists again (e.g. after a backup restore)
 *    is purged again.
 *
 * Future features that keep case records (reports, FR-054) must not block
 * this: they should reference the user with ON DELETE SET NULL or keep only
 * minimized identifiers.
 */
@Service
public class PrivacyService {

	private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

	private final AppUserRepository users;

	private final AccountService accounts;

	private final AccountMailer mailer;

	private final DeletionLedger ledger;

	private final PrivacyProperties props;

	private final JdbcTemplate jdbc;

	private final TransactionTemplate tx;

	private final Clock clock;

	public PrivacyService(AppUserRepository users, AccountService accounts, AccountMailer mailer,
			DeletionLedger ledger, PrivacyProperties props, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
		this.users = users;
		this.accounts = accounts;
		this.mailer = mailer;
		this.ledger = ledger;
		this.props = props;
		this.jdbc = jdbc;
		this.tx = tx;
		this.clock = clock;
	}

	/** Returns when the account will be deleted. Requires the current password. */
	public Instant requestDeletion(AuthPrincipal principal, String password) {
		if (principal.accountType() != AccountType.READER) {
			throw new IllegalStateException("staff accounts are removed by a system administrator");
		}
		if (!accounts.verifyPassword(principal.userId(), password)) {
			throw new IllegalArgumentException("password is incorrect");
		}
		Instant now = clock.instant();
		Instant due = now.plus(props.deletionRecoveryPeriod());
		String email = tx.execute(status -> {
			AppUser user = users.findById(principal.userId()).orElseThrow();
			user.requestDeletion(now, due);
			return user.getEmail();
		});
		mailer.sendDeletionScheduled(email, due);
		return due;
	}

	/** Purges every account whose recovery period has ended. Returns how many. */
	public int purgeDue() {
		List<Long> due = jdbc.queryForList(
				"SELECT id FROM app_user WHERE status = 'DELETION_PENDING' AND deletion_due_at <= ? ORDER BY id",
				Long.class, clock.instant().atOffset(ZoneOffset.UTC));
		int purged = 0;
		for (Long id : due) {
			try {
				purge(id);
				purged++;
			}
			catch (RuntimeException ex) {
				log.error("Could not purge account {}", id, ex);
			}
		}
		return purged;
	}

	/** Re-applies recorded deletions, e.g. after restoring an older database backup (FR-055). */
	public int reapplyLedger() {
		Set<Long> ids = ledger.allIds();
		if (ids.isEmpty()) {
			return 0;
		}
		int reapplied = 0;
		for (Long id : ids) {
			Boolean present = jdbc.queryForObject(
					"SELECT EXISTS (SELECT 1 FROM app_user WHERE id = ? AND status <> 'DELETED')", Boolean.class, id);
			if (Boolean.TRUE.equals(present)) {
				purge(id);
				reapplied++;
			}
		}
		if (reapplied > 0) {
			log.warn("Re-applied {} recorded account deletion(s) after a restore", reapplied);
		}
		return reapplied;
	}

	void purge(long userId) {
		Instant now = clock.instant();
		String email = jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, userId);
		try {
			// Cascades to preferences, bookmarks, collections, history and tokens.
			tx.executeWithoutResult(status -> jdbc.update("DELETE FROM app_user WHERE id = ?", userId));
		}
		catch (DataIntegrityViolationException stillReferenced) {
			tx.executeWithoutResult(status -> anonymize(userId));
		}
		ledger.record(userId, now);
		if (email != null && !email.endsWith("@deleted.invalid")) {
			mailer.sendAccountDeleted(email);
		}
		log.info("Purged account {}", userId);
	}

	private void anonymize(long userId) {
		for (String table : List.of("bookmark", "collection", "reading_history", "user_language", "user_topic",
				"auth_token")) {
			jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", userId);
		}
		jdbc.update("""
				UPDATE app_user SET email = 'deleted-' || id || '@deleted.invalid', password_hash = '!deleted',
				       status = ?, security_stamp = ?, digest_language = NULL, history_paused = FALSE,
				       last_login_at = NULL, locked_until = NULL, failed_login_count = 0,
				       deletion_requested_at = NULL, deletion_due_at = NULL
				WHERE id = ?
				""", Status.DELETED.name(), Tokens.newStamp(), userId);
	}

}
