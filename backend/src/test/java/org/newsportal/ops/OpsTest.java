package org.newsportal.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.newsportal.TestClockConfig.MutableClock;
import org.newsportal.TestMailConfig.CapturingMailGateway;
import org.newsportal.ops.AlertManager.Change;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real pg_dump / pg_restore against the test database and its scratch
 * restore database (TEST_RESTORE_DB_URL).
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsTest {

	private static final Instant START = Instant.parse("2026-10-01T02:30:00Z");

	private static final String ALERT_TO = "ops-alerts@example.org";

	@Autowired
	private BackupService backups;

	@Autowired
	private AlertManager alerts;

	@Autowired
	private OpsMonitor monitor;

	@Autowired
	private OpsProperties props;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MutableClock clock;

	@Autowired
	private CapturingMailGateway mail;

	@Value("${spring.datasource.url}")
	private String url;

	@Value("${spring.datasource.username}")
	private String username;

	@Value("${spring.datasource.password}")
	private String password;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(START);
		mail.drain(ALERT_TO);
	}

	@Test
	void backupWritesAVerifiedDumpAndRecordsIt() throws Exception {
		BackupService.RunResult result = backups.backup();

		assertThat(result.success()).as(result.detail()).isTrue();
		Path dump = backups.currentBackupDir().resolve(result.fileName());
		assertThat(dump).exists();
		assertThat(Files.size(dump)).isPositive();
		assertThat(backups.currentBackupDir().resolve(result.fileName() + ".partial")).doesNotExist();
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT outcome, sha256, size_bytes FROM ops_run WHERE kind = 'BACKUP' AND file_name = ?", result.fileName());
		assertThat(row.get("outcome")).isEqualTo("SUCCESS");
		assertThat((String) row.get("sha256")).matches("[0-9a-f]{64}");
	}

	@Test
	void restoreTestLoadsTheNewestBackupIntoTheScratchDatabase() throws Exception {
		assertThat(backups.backup().success()).isTrue();

		BackupService.RunResult result = backups.restoreTest();

		assertThat(result.success()).as(result.detail()).isTrue();
		try (Connection c = DriverManager.getConnection(props.restoreTest().dbUrl(), username, password);
				ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM category")) {
			rs.next();
			assertThat(rs.getInt(1)).isEqualTo(12);
		}
	}

	@Test
	void restoreTestRefusesToTargetTheLiveDatabase() {
		OpsProperties pointingAtLive = new OpsProperties(props.pgBinDir(), props.backup(),
				new OpsProperties.RestoreTest(false, url, "0 0 0 * * *", Duration.ofDays(8)), props.monitor());
		BackupService dangerous = new BackupService(pointingAtLive, jdbc, clock, url, username, password,
				"target/test-deletion-ledger.txt");

		BackupService.RunResult result = dangerous.restoreTest();

		assertThat(result.success()).isFalse();
		assertThat(result.detail()).contains("refused");
	}

	@Test
	void pruneRemovesExpiredDumpsButKeepsTheNewest() throws Exception {
		Path dir = Files.createDirectories(backups.currentBackupDir());
		String db = "prunetest" + UUID.randomUUID().toString().substring(0, 6);
		Path old1 = Files.writeString(dir.resolve("newsportal-" + db + "-20000101T000000Z.dump"), "x");
		Path old2 = Files.writeString(dir.resolve("newsportal-" + db + "-20000102T000000Z.dump"), "x");
		FileTime ancient = FileTime.from(START.minus(Duration.ofDays(30)));
		Files.setLastModifiedTime(old1, ancient);
		Files.setLastModifiedTime(old2, ancient);
		assertThat(backups.backup().success()).isTrue();

		backups.prune();

		assertThat(old1).doesNotExist();
		assertThat(old2).doesNotExist();
		assertThat(backups.newestBackup()).exists();
	}

	@Test
	void alertsAreSentOnceThenRemindedAfterCooldownThenResolved() throws Exception {
		String key = "test.alert." + UUID.randomUUID();

		List<Change> first = alerts.evaluate(Map.of(key, "Something broke"));
		assertThat(first).extracting(Change::kind).contains("NEW");
		assertThat(mail.await(ALERT_TO, "problem").body()).contains("[NEW] Something broke");

		clock.set(START.plus(Duration.ofHours(1)));
		assertThat(alerts.evaluate(Map.of(key, "Something broke"))).noneMatch(c -> c.key().equals(key));

		clock.set(START.plus(props.monitor().alertCooldown()));
		assertThat(alerts.evaluate(Map.of(key, "Something broke"))).anyMatch(c -> c.kind().equals("STILL_OPEN"));
		assertThat(mail.await(ALERT_TO, "problem").body()).contains("[STILL OPEN] Something broke");

		assertThat(alerts.evaluate(Map.of())).anyMatch(c -> c.key().equals(key) && c.kind().equals("RESOLVED"));
		assertThat(mail.await(ALERT_TO, "resolved").body()).contains("[RESOLVED] Something broke");
		assertThat(jdbc.queryForObject("SELECT status FROM ops_alert WHERE alert_key = ?", String.class, key))
			.isEqualTo("RESOLVED");
	}

	@Test
	void monitorFlagsStaleBackupsAndClearsOnceBackupsAreFresh() throws Exception {
		assertThat(backups.backup().success()).isTrue();
		assertThat(backups.restoreTest().success()).isTrue();
		assertThat(monitor.check()).doesNotContainKeys("backup.stale", "backup.failed", "restore-test.stale",
				"restore-test.failed", "restore-test.not-configured");

		clock.set(START.plus(props.backup().maxAge()).plusSeconds(60));
		assertThat(monitor.check()).containsKey("backup.stale");

		assertThat(backups.backup().success()).isTrue();
		assertThat(monitor.check()).doesNotContainKey("backup.stale");
	}

}
