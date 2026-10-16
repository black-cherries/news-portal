package org.newsportal.identity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A single-use, expiring token (FR-040). Only its hash is stored. */
@Entity
@Table(name = "auth_token")
public class AuthToken {

	public enum Purpose {
		VERIFY_EMAIL, RESET_PASSWORD
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Purpose purpose;

	@Column(name = "token_hash", nullable = false, unique = true, length = 64)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AuthToken() {
	}

	AuthToken(Long userId, Purpose purpose, String tokenHash, Instant now, Instant expiresAt) {
		this.userId = userId;
		this.purpose = purpose;
		this.tokenHash = tokenHash;
		this.createdAt = now;
		this.expiresAt = expiresAt;
	}

	boolean isUsable(Instant now) {
		return usedAt == null && now.isBefore(expiresAt);
	}

	void markUsed(Instant now) {
		usedAt = now;
	}

	Long getUserId() {
		return userId;
	}

	Purpose getPurpose() {
		return purpose;
	}

}
