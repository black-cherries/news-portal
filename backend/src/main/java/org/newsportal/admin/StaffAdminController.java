package org.newsportal.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.newsportal.identity.AuthPrincipal;
import org.newsportal.identity.StaffService;
import org.newsportal.identity.StaffService.StaffView;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Staff accounts and the audit log. System administrators only. */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class StaffAdminController {

	private final StaffService staff;

	private final JdbcTemplate jdbc;

	public StaffAdminController(StaffService staff, JdbcTemplate jdbc) {
		this.staff = staff;
		this.jdbc = jdbc;
	}

	public record InviteRequest(String email, List<String> roles) {
	}

	public record RolesRequest(List<String> roles) {
	}

	public record AuditEntry(long id, Long actorUserId, String actorEmail, String action, String targetType,
			String targetId, String detail, Instant createdAt) {
	}

	@GetMapping("/staff")
	public List<StaffView> list() {
		return staff.list();
	}

	@PostMapping("/staff")
	@ResponseStatus(HttpStatus.CREATED)
	public StaffView invite(@RequestBody InviteRequest body, @AuthenticationPrincipal AuthPrincipal me) {
		return staff.invite(body.email(), body.roles(), me);
	}

	@PutMapping("/staff/{id}/roles")
	public StaffView roles(@PathVariable long id, @RequestBody RolesRequest body,
			@AuthenticationPrincipal AuthPrincipal me) {
		return staff.setRoles(id, body.roles(), me);
	}

	@PostMapping("/staff/{id}/disable")
	public StaffView disable(@PathVariable long id, @AuthenticationPrincipal AuthPrincipal me) {
		return staff.disable(id, me);
	}

	@PostMapping("/staff/{id}/enable")
	public StaffView enable(@PathVariable long id, @AuthenticationPrincipal AuthPrincipal me) {
		return staff.enable(id, me);
	}

	/** Newest first. Pass the last id seen as {@code before} to page back. */
	@GetMapping("/audit")
	public List<AuditEntry> audit(@RequestParam(required = false) Long before,
			@RequestParam(defaultValue = "50") int limit) {
		int size = Math.clamp(limit, 1, 200);
		return jdbc.query("""
				SELECT a.id, a.actor_user_id, u.email, a.action, a.target_type, a.target_id, a.detail, a.created_at
				FROM audit_log a LEFT JOIN app_user u ON u.id = a.actor_user_id
				WHERE (?::bigint IS NULL OR a.id < ?)
				ORDER BY a.id DESC LIMIT ?
				""", (rs, n) -> new AuditEntry(rs.getLong("id"), (Long) rs.getObject("actor_user_id"),
				rs.getString("email"), rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
				rs.getString("detail"), rs.getObject("created_at", OffsetDateTime.class).toInstant()),
				before, before, size);
	}

}
