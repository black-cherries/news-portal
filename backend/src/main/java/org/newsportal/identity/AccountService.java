package org.newsportal.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.newsportal.identity.AppUser.AccountType;
import org.newsportal.identity.AppUser.Status;
import org.newsportal.identity.AuthToken.Purpose;
import org.newsportal.mail.AccountMailer;
import org.newsportal.web.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, verification, sign-in and password recovery (SRS §5.6).
 *
 * Responses never reveal whether an email address has an account (FR-042):
 * register, resend and reset-request behave the same either way, and sign-in
 * spends the same hashing effort for unknown addresses.
 */
@Service
@Transactional
public class AccountService {

	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	static final Duration VERIFY_TTL = Duration.ofHours(24);

	static final Duration RESET_TTL = Duration.ofHours(1);

	static final int MAX_FAILED_LOGINS = 10;

	static final Duration LOCK_FOR = Duration.ofMinutes(15);

	private static final Set<String> UI_LANGUAGES = Set.of("en", "hi", "ur", "ar");

	private final AppUserRepository users;

	private final AuthTokenRepository tokens;

	private final PasswordEncoder encoder;

	private final RateLimiter limiter;

	private final AccountMailer mailer;

	private final AuditLog audit;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private final String dummyHash;

	public AccountService(AppUserRepository users, AuthTokenRepository tokens, PasswordEncoder encoder,
			RateLimiter limiter, AccountMailer mailer, AuditLog audit, JdbcTemplate jdbc, Clock clock) {
		this.users = users;
		this.tokens = tokens;
		this.encoder = encoder;
		this.limiter = limiter;
		this.mailer = mailer;
		this.audit = audit;
		this.jdbc = jdbc;
		this.clock = clock;
		this.dummyHash = encoder.encode(Tokens.newToken());
	}

	// --- registration and verification ---

	public void register(String rawEmail, String password, boolean ageConfirmed, String uiLanguage, String ip) {
		limiter.acquire("register:ip:" + ip, 10, Duration.ofHours(1));
		if (!ageConfirmed) {
			throw new InvalidRequestException("you must confirm that you are 16 or older");
		}
		String language = (uiLanguage == null) ? "en" : uiLanguage;
		if (!UI_LANGUAGES.contains(language)) {
			throw new InvalidRequestException("unsupported language");
		}
		String email = Credentials.normalizeEmail(rawEmail);
		Credentials.checkPassword(password, email);
		limiter.acquire("mail:" + email, 3, Duration.ofHours(1));

		// Hash before the lookup so both paths cost the same.
		String hash = encoder.encode(password);
		Optional<AppUser> existing = users.findByEmail(email);
		if (existing.isPresent()) {
			AppUser user = existing.get();
			if (user.getStatus() == Status.PENDING_VERIFICATION) {
				sendVerification(user);
			}
			else if (user.getStatus() == Status.ACTIVE) {
				mailer.sendAccountExists(email);
			}
			return;
		}
		try {
			AppUser user = users.saveAndFlush(new AppUser(AccountType.READER, email, hash, language, clock.instant()));
			sendVerification(user);
		}
		catch (DataIntegrityViolationException race) {
			log.info("Concurrent registration for the same address; ignoring the second");
		}
	}

	/** Returns false for an unknown, used or expired token. */
	public boolean verifyEmail(String rawToken, String ip) {
		limiter.acquire("token:ip:" + ip, 30, Duration.ofHours(1));
		Optional<AuthToken> token = usableToken(rawToken, Purpose.VERIFY_EMAIL);
		if (token.isEmpty()) {
			return false;
		}
		Instant now = clock.instant();
		token.get().markUsed(now);
		users.findById(token.get().getUserId()).ifPresent(u -> u.markEmailVerified(now));
		return true;
	}

	public void resendVerification(String rawEmail, String ip) {
		limiter.acquire("resend:ip:" + ip, 10, Duration.ofHours(1));
		String email = Credentials.normalizeEmail(rawEmail);
		limiter.acquire("mail:" + email, 3, Duration.ofHours(1));
		users.findByEmail(email)
			.filter(u -> u.getStatus() == Status.PENDING_VERIFICATION)
			.ifPresent(this::sendVerification);
	}

	// --- password recovery ---

	public void requestPasswordReset(String rawEmail, String ip) {
		limiter.acquire("reset:ip:" + ip, 10, Duration.ofHours(1));
		String email = Credentials.normalizeEmail(rawEmail);
		limiter.acquire("mail:" + email, 3, Duration.ofHours(1));
		users.findByEmail(email).filter(u -> u.getStatus() != Status.DISABLED).ifPresent(user -> {
			String token = issueToken(user, Purpose.RESET_PASSWORD, RESET_TTL);
			mailer.sendPasswordReset(user.getEmail(), token, RESET_TTL);
		});
	}

	/** Returns false for an unknown, used or expired token. Ends all sessions of the user. */
	public boolean resetPassword(String rawToken, String newPassword, String ip) {
		limiter.acquire("token:ip:" + ip, 30, Duration.ofHours(1));
		Optional<AuthToken> token = usableToken(rawToken, Purpose.RESET_PASSWORD);
		if (token.isEmpty()) {
			return false;
		}
		AppUser user = users.findById(token.get().getUserId()).orElse(null);
		if (user == null || user.getStatus() == Status.DISABLED) {
			return false;
		}
		Credentials.checkPassword(newPassword, user.getEmail());
		Instant now = clock.instant();
		token.get().markUsed(now);
		user.changePassword(encoder.encode(newPassword), now);
		// The reset link proves the person controls the mailbox.
		user.markEmailVerified(now);
		if (user.getAccountType() == AccountType.STAFF) {
			audit.record(user.getId(), "PASSWORD_RESET", "app_user", user.getId(), null);
		}
		mailer.sendPasswordChanged(user.getEmail());
		return true;
	}

	/** Returns the updated principal so the current session can stay signed in; other sessions end. */
	public AuthPrincipal changePassword(AuthPrincipal principal, String currentPassword, String newPassword) {
		AppUser user = users.findById(principal.userId()).orElseThrow();
		if (currentPassword == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
			throw new InvalidRequestException("current password is incorrect");
		}
		Credentials.checkPassword(newPassword, user.getEmail());
		user.changePassword(encoder.encode(newPassword), clock.instant());
		if (user.getAccountType() == AccountType.STAFF) {
			audit.record(user.getId(), "PASSWORD_CHANGED", "app_user", user.getId(), null);
		}
		mailer.sendPasswordChanged(user.getEmail());
		return toPrincipal(user);
	}

	// --- sign-in ---

	public enum LoginOutcome {
		SUCCESS, INVALID_CREDENTIALS, EMAIL_NOT_VERIFIED,
		/** Correct password, but the account is scheduled for deletion and can still be restored. */
		DELETION_PENDING
	}

	public record LoginResult(LoginOutcome outcome, AuthPrincipal principal, Instant deletionDueAt) {

		LoginResult(LoginOutcome outcome, AuthPrincipal principal) {
			this(outcome, principal, null);
		}

	}

	public LoginResult authenticate(String rawEmail, String password, String ip) {
		limiter.acquire("login:ip:" + ip, 50, Duration.ofMinutes(15));
		String email;
		try {
			email = Credentials.normalizeEmail(rawEmail);
		}
		catch (InvalidRequestException ex) {
			encoder.matches("x", dummyHash);
			return new LoginResult(LoginOutcome.INVALID_CREDENTIALS, null);
		}
		// Above MAX_FAILED_LOGINS so the account lock, not this limit, handles password guessing.
		limiter.acquire("login:email:" + email, 20, Duration.ofMinutes(15));
		String given = (password == null) ? "" : password;
		Instant now = clock.instant();

		AppUser user = users.findByEmail(email).orElse(null);
		if (user == null || user.isLocked(now)) {
			encoder.matches(given, dummyHash);
			return new LoginResult(LoginOutcome.INVALID_CREDENTIALS, null);
		}
		if (!encoder.matches(given, user.getPasswordHash())) {
			user.recordFailedLogin(now, MAX_FAILED_LOGINS, LOCK_FOR);
			if (user.isLocked(now)) {
				log.warn("User {} locked for {} after repeated failed sign-ins", user.getId(), LOCK_FOR);
			}
			return new LoginResult(LoginOutcome.INVALID_CREDENTIALS, null);
		}
		if (user.getStatus() == Status.DISABLED || user.getStatus() == Status.DELETED) {
			return new LoginResult(LoginOutcome.INVALID_CREDENTIALS, null);
		}
		if (user.getStatus() == Status.DELETION_PENDING) {
			return new LoginResult(LoginOutcome.DELETION_PENDING, null, user.getDeletionDueAt());
		}
		if (user.getStatus() == Status.PENDING_VERIFICATION) {
			return new LoginResult(LoginOutcome.EMAIL_NOT_VERIFIED, null);
		}
		user.recordSuccessfulLogin(now);
		if (encoder.upgradeEncoding(user.getPasswordHash())) {
			user.changePasswordHashOnly(encoder.encode(given));
		}
		if (user.getAccountType() == AccountType.STAFF) {
			audit.record(user.getId(), "STAFF_SIGN_IN", "app_user", user.getId(), null);
		}
		return new LoginResult(LoginOutcome.SUCCESS, toPrincipal(user));
	}

	/** Re-confirms the signed-in user's password before sensitive actions (export, deletion). */
	@Transactional(readOnly = true)
	public boolean verifyPassword(long userId, String password) {
		AppUser user = users.findById(userId).orElse(null);
		if (user == null || password == null) {
			encoder.matches("x", dummyHash);
			return false;
		}
		return encoder.matches(password, user.getPasswordHash());
	}

	/**
	 * Cancels a pending deletion when the owner proves the password (FR-052).
	 * Returns false otherwise, without saying why.
	 */
	public boolean restoreAccount(String rawEmail, String password, String ip) {
		limiter.acquire("login:ip:" + ip, 50, Duration.ofMinutes(15));
		String email;
		try {
			email = Credentials.normalizeEmail(rawEmail);
		}
		catch (InvalidRequestException ex) {
			return false;
		}
		limiter.acquire("login:email:" + email, 20, Duration.ofMinutes(15));
		AppUser user = users.findByEmail(email).orElse(null);
		Instant now = clock.instant();
		if (user == null || user.isLocked(now)) {
			encoder.matches(password == null ? "" : password, dummyHash);
			return false;
		}
		if (password == null || !encoder.matches(password, user.getPasswordHash())) {
			user.recordFailedLogin(now, MAX_FAILED_LOGINS, LOCK_FOR);
			return false;
		}
		if (user.getStatus() != Status.DELETION_PENDING) {
			return false;
		}
		user.cancelDeletion();
		return true;
	}

	/** Re-checks a session on each request: the account must still be active and unchanged. */
	@Transactional(readOnly = true)
	public Optional<AuthPrincipal> refresh(long userId, String securityStamp) {
		return users.findById(userId)
			.filter(u -> u.getStatus() == Status.ACTIVE)
			.filter(u -> u.getSecurityStamp().equals(securityStamp))
			.map(this::toPrincipal);
	}

	// --- staff accounts ---

	/** Creates an active staff account. Only for trusted operator tooling (see AdminBootstrap). */
	public AppUser createStaffAccount(String rawEmail, String password, List<String> roles, Long actorUserId) {
		String email = Credentials.normalizeEmail(rawEmail);
		Credentials.checkPassword(password, email);
		if (users.findByEmail(email).isPresent()) {
			throw new IllegalStateException("an account with this email already exists");
		}
		Instant now = clock.instant();
		AppUser user = new AppUser(AccountType.STAFF, email, encoder.encode(password), "en", now);
		user.markEmailVerified(now);
		user = users.saveAndFlush(user);
		for (String role : roles) {
			int granted = jdbc.update("""
					INSERT INTO user_role (user_id, role_id, granted_by)
					SELECT ?, id, ? FROM role WHERE code = ?
					""", user.getId(), actorUserId, role);
			if (granted != 1) {
				throw new IllegalArgumentException("unknown role " + role);
			}
		}
		audit.record(actorUserId, "STAFF_ACCOUNT_CREATED", "app_user", user.getId(), "roles=" + roles);
		return user;
	}

	// --- helpers ---

	private void sendVerification(AppUser user) {
		String token = issueToken(user, Purpose.VERIFY_EMAIL, VERIFY_TTL);
		mailer.sendVerification(user.getEmail(), token, VERIFY_TTL);
	}

	private String issueToken(AppUser user, Purpose purpose, Duration ttl) {
		Instant now = clock.instant();
		tokens.retireOpenTokens(user.getId(), purpose, now);
		String raw = Tokens.newToken();
		tokens.save(new AuthToken(user.getId(), purpose, Tokens.sha256Hex(raw), now, now.plus(ttl)));
		return raw;
	}

	private Optional<AuthToken> usableToken(String rawToken, Purpose purpose) {
		if (rawToken == null || rawToken.isBlank() || rawToken.length() > 100) {
			return Optional.empty();
		}
		return tokens.findByTokenHash(Tokens.sha256Hex(rawToken.strip()))
			.filter(t -> t.getPurpose() == purpose)
			.filter(t -> t.isUsable(clock.instant()));
	}

	private AuthPrincipal toPrincipal(AppUser user) {
		Set<String> roles = new LinkedHashSet<>(jdbc.queryForList(
				"SELECT r.code FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = ? ORDER BY r.code",
				String.class, user.getId()));
		return new AuthPrincipal(user.getId(), user.getEmail(), user.getAccountType(), Set.copyOf(roles),
				user.getSecurityStamp());
	}

}
