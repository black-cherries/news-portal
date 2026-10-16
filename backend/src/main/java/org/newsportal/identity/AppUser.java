package org.newsportal.identity;

import java.time.Instant;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "app_user")
// Only changed columns are written, so updates here never overwrite
// preference columns that MeService maintains with plain SQL.
@DynamicUpdate
public class AppUser {

	public enum AccountType {
		READER, STAFF
	}

	public enum Status {
		PENDING_VERIFICATION, ACTIVE, DISABLED,
		/** Signed out and blocked; restorable until {@code deletionDueAt} (FR-052). */
		DELETION_PENDING,
		/** Anonymized placeholder kept only where other records still point to it (FR-053). */
		DELETED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "account_type", nullable = false, length = 10, updatable = false)
	private AccountType accountType;

	@Column(nullable = false, unique = true, length = 254)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private Status status;

	@Column(name = "ui_language", nullable = false, length = 8)
	private String uiLanguage;

	@Column(name = "age_declared_at", nullable = false)
	private Instant ageDeclaredAt;

	@Column(name = "email_verified_at")
	private Instant emailVerifiedAt;

	@Column(name = "security_stamp", nullable = false, length = 64)
	private String securityStamp;

	@Column(name = "failed_login_count", nullable = false)
	private int failedLoginCount;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "password_changed_at", nullable = false)
	private Instant passwordChangedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "deletion_requested_at")
	private Instant deletionRequestedAt;

	@Column(name = "deletion_due_at")
	private Instant deletionDueAt;

	@Version
	private long version;

	protected AppUser() {
	}

	AppUser(AccountType accountType, String email, String passwordHash, String uiLanguage, Instant now) {
		this.accountType = accountType;
		this.email = email;
		this.passwordHash = passwordHash;
		this.uiLanguage = uiLanguage;
		this.status = Status.PENDING_VERIFICATION;
		this.ageDeclaredAt = now;
		this.passwordChangedAt = now;
		this.createdAt = now;
		this.securityStamp = Tokens.newStamp();
	}

	void markEmailVerified(Instant now) {
		if (emailVerifiedAt == null) {
			emailVerifiedAt = now;
		}
		if (status == Status.PENDING_VERIFICATION) {
			status = Status.ACTIVE;
		}
	}

	/** Also ends every existing session for this user. */
	void changePassword(String newHash, Instant now) {
		passwordHash = newHash;
		passwordChangedAt = now;
		failedLoginCount = 0;
		lockedUntil = null;
		securityStamp = Tokens.newStamp();
	}

	/** Re-hash with stronger settings after a successful sign-in; sessions stay valid. */
	void changePasswordHashOnly(String newHash) {
		passwordHash = newHash;
	}

	void disable() {
		status = Status.DISABLED;
		securityStamp = Tokens.newStamp();
	}

	/** Blocks access immediately and ends every session. */
	void requestDeletion(Instant now, Instant due) {
		status = Status.DELETION_PENDING;
		deletionRequestedAt = now;
		deletionDueAt = due;
		securityStamp = Tokens.newStamp();
	}

	void cancelDeletion() {
		status = (emailVerifiedAt == null) ? Status.PENDING_VERIFICATION : Status.ACTIVE;
		deletionRequestedAt = null;
		deletionDueAt = null;
	}

	public Instant getDeletionDueAt() {
		return deletionDueAt;
	}

	void enable() {
		if (status == Status.DISABLED) {
			status = (emailVerifiedAt == null) ? Status.PENDING_VERIFICATION : Status.ACTIVE;
		}
	}

	void recordFailedLogin(Instant now, int maxFailures, java.time.Duration lockFor) {
		failedLoginCount++;
		if (failedLoginCount >= maxFailures) {
			lockedUntil = now.plus(lockFor);
			failedLoginCount = 0;
		}
	}

	void recordSuccessfulLogin(Instant now) {
		failedLoginCount = 0;
		lockedUntil = null;
		lastLoginAt = now;
	}

	boolean isLocked(Instant now) {
		return lockedUntil != null && now.isBefore(lockedUntil);
	}

	public Long getId() {
		return id;
	}

	public AccountType getAccountType() {
		return accountType;
	}

	public String getEmail() {
		return email;
	}

	String getPasswordHash() {
		return passwordHash;
	}

	public Status getStatus() {
		return status;
	}

	public String getUiLanguage() {
		return uiLanguage;
	}

	public Instant getEmailVerifiedAt() {
		return emailVerifiedAt;
	}

	public String getSecurityStamp() {
		return securityStamp;
	}

	public Instant getLastLoginAt() {
		return lastLoginAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
