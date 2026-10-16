package org.newsportal.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

import org.newsportal.identity.AppUser.AccountType;
import org.newsportal.identity.AuthToken.Purpose;
import org.newsportal.mail.AccountMailer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff account administration for system administrators (SRS §4.3). New
 * staff are invited by email and choose their own password; administrators
 * never see or set it.
 */
@Service
@Transactional
public class StaffService {

	static final Duration INVITE_TTL = Duration.ofHours(72);

	public static final Set<String> ROLES = Set.of("SYSTEM_ADMIN", "SOURCE_MANAGER", "CONTENT_REVIEWER", "ANALYST");

	private final AppUserRepository users;

	private final AuthTokenRepository tokens;

	private final PasswordEncoder encoder;

	private final AccountMailer mailer;

	private final AuditLog audit;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public StaffService(AppUserRepository users, AuthTokenRepository tokens, PasswordEncoder encoder,
			AccountMailer mailer, AuditLog audit, JdbcTemplate jdbc, Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.encoder = encoder;
		this.mailer = mailer;
		this.audit = audit;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public record StaffView(long id, String email, String status, List<String> roles, Instant lastLoginAt,
			Instant createdAt) {
	}

	@Transactional(readOnly = true)
	public List<StaffView> list() {
		return users.findAll().stream()
			.filter(u -> u.getAccountType() == AccountType.STAFF)
			.sorted((a, b) -> a.getEmail().compareTo(b.getEmail()))
			.map(this::view)
			.toList();
	}

	/** Creates a pending staff account and emails a link to set a password. */
	public StaffView invite(String rawEmail, List<String> roles, AuthPrincipal actor) {
		String email = Credentials.normalizeEmail(rawEmail);
		Set<String> wanted = checkRoles(roles);
		if (users.findByEmail(email).isPresent()) {
			throw new IllegalStateException("an account with this email already exists");
		}
		Instant now = clock.instant();
		// Unusable random password until the invitee sets their own.
		AppUser user = users.saveAndFlush(
				new AppUser(AccountType.STAFF, email, encoder.encode(Tokens.newToken()), "en", now));
		setRolesInternal(user.getId(), wanted, actor.userId());

		String raw = Tokens.newToken();
		tokens.save(new AuthToken(user.getId(), Purpose.RESET_PASSWORD, Tokens.sha256Hex(raw), now, now.plus(INVITE_TTL)));
		mailer.sendStaffInvite(email, raw, INVITE_TTL);
		audit.record(actor.userId(), "STAFF_INVITED", "app_user", user.getId(), "roles=" + wanted);
		return view(user);
	}

	public StaffView setRoles(long userId, List<String> roles, AuthPrincipal actor) {
		AppUser user = findStaff(userId);
		Set<String> wanted = checkRoles(roles);
		if (userId == actor.userId() && !wanted.contains("SYSTEM_ADMIN")) {
			throw new IllegalStateException("you cannot remove your own SYSTEM_ADMIN role");
		}
		if (!wanted.contains("SYSTEM_ADMIN")) {
			requireAnotherActiveAdmin(userId);
		}
		setRolesInternal(userId, wanted, actor.userId());
		audit.record(actor.userId(), "STAFF_ROLES_CHANGED", "app_user", userId, "roles=" + wanted);
		return view(user);
	}

	/** Blocks sign-in and ends every session of the account immediately. */
	public StaffView disable(long userId, AuthPrincipal actor) {
		AppUser user = findStaff(userId);
		if (userId == actor.userId()) {
			throw new IllegalStateException("you cannot disable your own account");
		}
		requireAnotherActiveAdmin(userId);
		user.disable();
		audit.record(actor.userId(), "STAFF_DISABLED", "app_user", userId, null);
		return view(user);
	}

	public StaffView enable(long userId, AuthPrincipal actor) {
		AppUser user = findStaff(userId);
		user.enable();
		audit.record(actor.userId(), "STAFF_ENABLED", "app_user", userId, null);
		return view(user);
	}

	private void setRolesInternal(long userId, Set<String> wanted, Long grantedBy) {
		jdbc.update("DELETE FROM user_role WHERE user_id = ?", userId);
		for (String role : wanted) {
			jdbc.update("INSERT INTO user_role (user_id, role_id, granted_by) SELECT ?, id, ? FROM role WHERE code = ?",
					userId, grantedBy, role);
		}
	}

	private void requireAnotherActiveAdmin(long excludingUserId) {
		Integer others = jdbc.queryForObject("""
				SELECT count(*) FROM user_role ur
				JOIN role r ON r.id = ur.role_id AND r.code = 'SYSTEM_ADMIN'
				JOIN app_user u ON u.id = ur.user_id AND u.status = 'ACTIVE'
				WHERE ur.user_id <> ?
				""", Integer.class, excludingUserId);
		boolean targetIsAdmin = Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id = ur.role_id
				               WHERE ur.user_id = ? AND r.code = 'SYSTEM_ADMIN')
				""", Boolean.class, excludingUserId));
		if (targetIsAdmin && (others == null || others == 0)) {
			throw new IllegalStateException("at least one active SYSTEM_ADMIN must remain");
		}
	}

	private static Set<String> checkRoles(List<String> roles) {
		if (roles == null || roles.isEmpty()) {
			throw new IllegalArgumentException("at least one role is required");
		}
		Set<String> wanted = new TreeSet<>(roles);
		for (String role : wanted) {
			if (!ROLES.contains(role)) {
				throw new IllegalArgumentException("unknown role " + role);
			}
		}
		return wanted;
	}

	private AppUser findStaff(long userId) {
		return users.findById(userId)
			.filter(u -> u.getAccountType() == AccountType.STAFF)
			.orElseThrow(() -> new NoSuchElementException("staff account " + userId + " not found"));
	}

	private StaffView view(AppUser u) {
		List<String> roles = jdbc.queryForList(
				"SELECT r.code FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = ? ORDER BY r.code",
				String.class, u.getId());
		return new StaffView(u.getId(), u.getEmail(), u.getStatus().name(), roles, u.getLastLoginAt(),
				u.getCreatedAt());
	}

}
