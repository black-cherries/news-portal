package org.newsportal.ops;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.newsportal.admin.AdminDtos.FeedHealthRow;
import org.newsportal.admin.AdminSourceService;
import org.newsportal.admin.FeedHealth;
import org.newsportal.source.SourceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Checks what FR-077 lists: database connectivity, feed health, backup and
 * restore-test freshness, and free disk space. Feed failures caused by rights
 * restrictions are not alerts (FR-079).
 */
@Service
public class OpsMonitor {

	private static final Logger log = LoggerFactory.getLogger(OpsMonitor.class);

	private final JdbcTemplate jdbc;

	private final AdminSourceService sources;

	private final BackupService backups;

	private final AlertManager alerts;

	private final OpsProperties props;

	private final Clock clock;

	private volatile Instant lastDatabaseAlert;

	public OpsMonitor(JdbcTemplate jdbc, AdminSourceService sources, BackupService backups, AlertManager alerts,
			OpsProperties props, Clock clock) {
		this.jdbc = jdbc;
		this.sources = sources;
		this.backups = backups;
		this.alerts = alerts;
		this.props = props;
		this.clock = clock;
	}

	/** Runs all checks and sends alerts for changes. Returns the problems found. */
	public Map<String, String> check() {
		try {
			jdbc.queryForObject("SELECT 1", Integer.class);
		}
		catch (DataAccessException ex) {
			databaseDown();
			return Map.of("database.unreachable", "The database cannot be reached.");
		}
		Map<String, String> problems = new LinkedHashMap<>();
		checkFeeds(problems);
		checkBackups(problems);
		checkRestoreTests(problems);
		checkDisk(problems);
		alerts.evaluate(problems);
		return problems;
	}

	private void checkFeeds(Map<String, String> problems) {
		for (FeedHealthRow row : sources.healthDashboard()) {
			if (row.sourceStatus() != SourceStatus.ACTIVE) {
				continue;
			}
			if (row.health() == FeedHealth.FAILING) {
				problems.put("feed.failing." + row.feedId(), "Feed for " + row.sourceCode() + " is failing ("
						+ row.consecutiveFailures() + " failures in a row, last: " + row.lastOutcome() + "): " + row.feedUrl());
			}
			else if (row.health() == FeedHealth.STALE) {
				problems.put("feed.stale." + row.feedId(), "Feed for " + row.sourceCode()
						+ " has had no successful fetch since " + row.lastSuccessAt() + ": " + row.feedUrl());
			}
		}
	}

	private void checkBackups(Map<String, String> problems) {
		Run last = lastRun("BACKUP", false);
		Run lastGood = lastRun("BACKUP", true);
		if (last != null && !last.success()) {
			problems.put("backup.failed", "The last backup failed: " + last.detail());
		}
		if (stale(lastGood, props.backup().maxAge())) {
			problems.put("backup.stale", "No successful backup in the last " + hours(props.backup().maxAge())
					+ " hours" + (lastGood == null ? " (none yet)." : "; last one finished " + lastGood.finishedAt() + "."));
		}
	}

	private void checkRestoreTests(Map<String, String> problems) {
		String url = props.restoreTest().dbUrl();
		if (url == null || url.isBlank()) {
			problems.put("restore-test.not-configured",
					"No restore-test database is configured, so backups are never verified by a restore.");
			return;
		}
		Run last = lastRun("RESTORE_TEST", false);
		Run lastGood = lastRun("RESTORE_TEST", true);
		if (last != null && !last.success()) {
			problems.put("restore-test.failed", "The last restore test failed: " + last.detail());
		}
		if (stale(lastGood, props.restoreTest().maxAge())) {
			problems.put("restore-test.stale", "No successful restore test in the last "
					+ props.restoreTest().maxAge().toDays() + " days.");
		}
	}

	private void checkDisk(Map<String, String> problems) {
		Path dir = backups.backupDir();
		try {
			Path existing = dir;
			while (existing != null && !Files.exists(existing)) {
				existing = existing.getParent();
			}
			if (existing == null) {
				return;
			}
			FileStore store = Files.getFileStore(existing);
			long freeMb = store.getUsableSpace() / (1024 * 1024);
			if (freeMb < props.monitor().minFreeDiskMb()) {
				problems.put("disk.backup-low", "Only " + freeMb + " MB free on the backup disk (" + dir
						+ "); the minimum is " + props.monitor().minFreeDiskMb() + " MB.");
			}
		}
		catch (IOException ex) {
			problems.put("disk.backup-unreadable", "Cannot read free space for " + dir + ": " + ex.getMessage());
		}
	}

	private void databaseDown() {
		Instant now = clock.instant();
		if (lastDatabaseAlert == null || !lastDatabaseAlert.plus(props.monitor().alertCooldown()).isAfter(now)) {
			lastDatabaseAlert = now;
			log.error("Database unreachable");
			alerts.sendDirect("[News Portal] database unreachable",
					"The News Portal backend cannot reach its database since about " + now + " UTC.\n");
		}
	}

	record Run(boolean success, Instant finishedAt, String detail) {
	}

	private Run lastRun(String kind, boolean successOnly) {
		List<Run> rows = jdbc.query("SELECT outcome, finished_at, detail FROM ops_run WHERE kind = ?"
				+ (successOnly ? " AND outcome = 'SUCCESS'" : "") + " ORDER BY finished_at DESC, id DESC LIMIT 1",
				(rs, n) -> new Run("SUCCESS".equals(rs.getString("outcome")),
						rs.getObject("finished_at", OffsetDateTime.class).toInstant(), rs.getString("detail")),
				kind);
		return rows.isEmpty() ? null : rows.getFirst();
	}

	private boolean stale(Run lastGood, Duration maxAge) {
		return lastGood == null || lastGood.finishedAt().plus(maxAge).isBefore(clock.instant());
	}

	private static long hours(Duration d) {
		return d.toHours();
	}

}
