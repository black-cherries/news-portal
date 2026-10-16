package org.newsportal.identity;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

	Optional<AuthToken> findByTokenHash(String tokenHash);

	/** Retires earlier tokens so only the newest one of a purpose works. */
	@Modifying
	@Query("update AuthToken t set t.usedAt = :now where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
	int retireOpenTokens(Long userId, AuthToken.Purpose purpose, Instant now);

}
