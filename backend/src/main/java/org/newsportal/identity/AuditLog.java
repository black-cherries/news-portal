package org.newsportal.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Append-only record of sensitive staff and security actions (FR-076). */
@Component
public class AuditLog {

	private final JdbcTemplate jdbc;

	public AuditLog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void record(Long actorUserId, String action, String targetType, Object targetId, String detail) {
		String safeDetail = (detail == null || detail.length() <= 500) ? detail : detail.substring(0, 500);
		jdbc.update("INSERT INTO audit_log (actor_user_id, action, target_type, target_id, detail) VALUES (?, ?, ?, ?, ?)",
				actorUserId, action, targetType, (targetId == null) ? null : targetId.toString(), safeDetail);
	}

}
