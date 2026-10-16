package org.newsportal.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.newsportal.identity.AuditLog;
import org.newsportal.identity.AuthPrincipal;
import org.newsportal.ops.BackupService;
import org.newsportal.ops.BackupService.RunResult;
import org.newsportal.ops.OpsMonitor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Backups, restore tests and alerts. System administrators only. */
@RestController
@RequestMapping("/api/v1/admin/ops")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminOpsController {

	private final BackupService backups;

	private final OpsMonitor monitor;

	private final JdbcTemplate jdbc;

	private final AuditLog audit;

	public AdminOpsController(BackupService backups, OpsMonitor monitor, JdbcTemplate jdbc, AuditLog audit) {
		this.backups = backups;
		this.monitor = monitor;
		this.jdbc = jdbc;
		this.audit = audit;
	}

	public record RunView(String kind, Instant startedAt, Instant finishedAt, String outcome, String fileName,
			Long sizeBytes, String sha256, String detail) {
	}

	public record AlertView(String key, String status, String message, Instant firstSeenAt, Instant lastSeenAt,
			Instant resolvedAt) {
	}

	@GetMapping
	public Map<String, Object> status() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("openAlerts", alerts("WHERE status = 'OPEN'"));
		out.put("recentlyResolvedAlerts", alerts("WHERE status = 'RESOLVED' ORDER BY resolved_at DESC LIMIT 10"));
		out.put("backups", runs("BACKUP", 14));
		out.put("restoreTests", runs("RESTORE_TEST", 5));
		Path dir = backupsDir();
		out.put("backupDir", dir.toString());
		try {
			Path existing = Files.exists(dir) ? dir : dir.getRoot();
			out.put("backupDiskFreeMb", Files.getFileStore(existing).getUsableSpace() / (1024 * 1024));
		}
		catch (IOException ex) {
			out.put("backupDiskFreeMb", null);
		}
		return out;
	}

	@PostMapping("/backup")
	public RunResult backupNow(@AuthenticationPrincipal AuthPrincipal me) {
		RunResult result = backups.backup();
		audit.record(me.userId(), "BACKUP_RUN_MANUALLY", "ops_run", null, result.success() ? "SUCCESS" : "FAILED");
		return result;
	}

	@PostMapping("/restore-test")
	public RunResult restoreTestNow(@AuthenticationPrincipal AuthPrincipal me) {
		RunResult result = backups.restoreTest();
		audit.record(me.userId(), "RESTORE_TEST_RUN_MANUALLY", "ops_run", null, result.success() ? "SUCCESS" : "FAILED");
		return result;
	}

	/** Runs the monitoring checks now (and sends any resulting alerts). */
	@PostMapping("/check")
	public Map<String, String> checkNow() {
		return monitor.check();
	}

	private Path backupsDir() {
		return backups.currentBackupDir();
	}

	private List<RunView> runs(String kind, int limit) {
		return jdbc.query("""
				SELECT kind, started_at, finished_at, outcome, file_name, size_bytes, sha256, detail
				FROM ops_run WHERE kind = ? ORDER BY started_at DESC, id DESC LIMIT ?
				""", (rs, n) -> new RunView(rs.getString("kind"), instant(rs.getObject("started_at", OffsetDateTime.class)),
				instant(rs.getObject("finished_at", OffsetDateTime.class)), rs.getString("outcome"),
				rs.getString("file_name"), (Long) rs.getObject("size_bytes"), rs.getString("sha256"),
				rs.getString("detail")), kind, limit);
	}

	private List<AlertView> alerts(String where) {
		return jdbc.query("SELECT alert_key, status, message, first_seen_at, last_seen_at, resolved_at FROM ops_alert " + where,
				(rs, n) -> new AlertView(rs.getString("alert_key"), rs.getString("status"), rs.getString("message"),
						instant(rs.getObject("first_seen_at", OffsetDateTime.class)),
						instant(rs.getObject("last_seen_at", OffsetDateTime.class)),
						instant(rs.getObject("resolved_at", OffsetDateTime.class))));
	}

	private static Instant instant(OffsetDateTime t) {
		return t == null ? null : t.toInstant();
	}

}
