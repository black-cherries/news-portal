package org.newsportal.ops;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Daily PostgreSQL backups and restore tests (SRS §12).
 *
 * A backup is a pg_dump custom-format archive. It is written under a temporary
 * name, checked with pg_restore --list, then renamed, so a half-written file is
 * never mistaken for a backup. The deletion ledger file is copied next to it
 * so deletions can be re-applied after a restore (FR-055).
 *
 * A restore test loads the newest backup into a separate scratch database and
 * checks it; "a backup shall not be considered reliable until a restoration
 * test succeeds".
 */
@Service
public class BackupService {

	private static final Logger log = LoggerFactory.getLogger(BackupService.class);

	static final String PREFIX = "newsportal-";

	static final String SUFFIX = ".dump";

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	private final OpsProperties props;

	private final PgTools tools;

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private final PgTools.Target live;

	private final Path ledgerFile;

	private final ReentrantLock lock = new ReentrantLock();

	public BackupService(OpsProperties props, JdbcTemplate jdbc, Clock clock,
			@Value("${spring.datasource.url}") String url, @Value("${spring.datasource.username}") String username,
			@Value("${spring.datasource.password}") String password,
			@Value("${newsportal.privacy.deletion-ledger-file}") String ledgerFile) {
		this.props = props;
		this.tools = new PgTools(props.pgBinDir());
		this.jdbc = jdbc;
		this.clock = clock;
		this.live = PgTools.Target.of(url, username, password);
		this.ledgerFile = Path.of(ledgerFile).toAbsolutePath().normalize();
	}

	public record RunResult(String kind, boolean success, String fileName, Long sizeBytes, String detail) {
	}

	/** Takes a backup now, then removes expired ones. Only one backup or restore test runs at a time. */
	public RunResult backup() {
		if (!lock.tryLock()) {
			return new RunResult("BACKUP", false, null, null, "another backup or restore test is running");
		}
		try {
			return doBackup();
		}
		finally {
			lock.unlock();
		}
	}

	private RunResult doBackup() {
		Instant started = clock.instant();
		Path dir = backupDir();
		// The random part keeps names unique even if two backups start in the same second.
		String name = PREFIX + live.database() + "-" + STAMP.format(started) + "-"
				+ java.util.UUID.randomUUID().toString().substring(0, 6) + SUFFIX;
		Path partial = dir.resolve(name + ".partial");
		Path target = dir.resolve(name);
		try {
			Files.createDirectories(dir);
			PgTools.Result dump = tools.run("pg_dump", live,
					List.of("--format=custom", "--compress=6", "--no-owner", "--no-privileges", "--file=" + partial), 60);
			if (!dump.ok()) {
				return record("BACKUP", started, false, null, null, null, "pg_dump failed: " + dump.output());
			}
			PgTools.Result check = tools.run("pg_restore", null, List.of("--list", partial.toString()), 10);
			if (!check.ok()) {
				return record("BACKUP", started, false, null, null, null, "archive check failed: " + check.output());
			}
			Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
			if (Files.exists(ledgerFile)) {
				Files.copy(ledgerFile, dir.resolve(name.replace(SUFFIX, ".deletion-ledger.txt")),
						StandardCopyOption.REPLACE_EXISTING);
			}
			long size = Files.size(target);
			RunResult result = record("BACKUP", started, true, name, size, sha256(target), null);
			prune();
			return result;
		}
		catch (IOException | InterruptedException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return record("BACKUP", started, false, null, null, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
		finally {
			try {
				Files.deleteIfExists(partial);
			}
			catch (IOException ignored) {
				// Best effort.
			}
		}
	}

	/** Restores the newest backup into the scratch database and checks it. */
	public RunResult restoreTest() {
		if (!lock.tryLock()) {
			return new RunResult("RESTORE_TEST", false, null, null, "another backup or restore test is running");
		}
		try {
			return doRestoreTest();
		}
		finally {
			lock.unlock();
		}
	}

	private RunResult doRestoreTest() {
		Instant started = clock.instant();
		String url = props.restoreTest().dbUrl();
		if (url == null || url.isBlank()) {
			return record("RESTORE_TEST", started, false, null, null, null, "no restore-test database configured");
		}
		PgTools.Target scratch;
		try {
			scratch = PgTools.Target.of(url, live.username(), live.password());
		}
		catch (IllegalArgumentException ex) {
			return record("RESTORE_TEST", started, false, null, null, null, "invalid restore-test database URL");
		}
		// Safety: never restore over the live database.
		if (scratch.database().equals(live.database()) && scratch.host().equals(live.host())
				&& scratch.port() == live.port()) {
			return record("RESTORE_TEST", started, false, null, null, null,
					"refused: restore-test database is the live database");
		}
		Path newest = newestBackup();
		if (newest == null) {
			return record("RESTORE_TEST", started, false, null, null, null, "no backup to test");
		}
		String name = newest.getFileName().toString();
		try {
			PgTools.Result restore = tools.run("pg_restore", scratch, List.of("--clean", "--if-exists", "--no-owner",
					"--no-privileges", "--single-transaction", "--exit-on-error", newest.toString()), 60);
			if (!restore.ok()) {
				return record("RESTORE_TEST", started, false, name, null, null, "pg_restore failed: " + restore.output());
			}
			String check = verifyRestored(url);
			if (check != null) {
				return record("RESTORE_TEST", started, false, name, null, null, check);
			}
			return record("RESTORE_TEST", started, true, name, Files.size(newest), null, "restored and verified");
		}
		catch (IOException | InterruptedException | SQLException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return record("RESTORE_TEST", started, false, name, null, null,
					ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
	}

	/**
	 * The restored database must have the same latest migration as the live
	 * one, and core tables must be readable. Returns a problem, or null.
	 */
	private String verifyRestored(String url) throws SQLException {
		String liveVersion = jdbc.queryForObject(
				"SELECT max(version::int)::text FROM flyway_schema_history WHERE success", String.class);
		try (Connection c = DriverManager.getConnection(url, live.username(), live.password());
				Statement s = c.createStatement()) {
			try (ResultSet rs = s.executeQuery("SELECT max(version::int)::text FROM flyway_schema_history WHERE success")) {
				rs.next();
				String restored = rs.getString(1);
				if (restored == null || !restored.equals(liveVersion)) {
					return "restored schema version " + restored + " does not match live version " + liveVersion;
				}
			}
			for (String table : List.of("news_source", "article", "app_user", "category")) {
				try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
					rs.next();
				}
			}
			try (ResultSet rs = s.executeQuery("SELECT count(*) FROM category")) {
				rs.next();
				if (rs.getInt(1) == 0) {
					return "restored database has no categories";
				}
			}
		}
		return null;
	}

	/** Deletes dumps older than the retention period, always keeping the newest one. */
	public int prune() {
		Path dir = backupDir();
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		Instant cutoff = clock.instant().minus(java.time.Duration.ofDays(props.backup().retentionDays()));
		Path newest = newestBackup();
		int removed = 0;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(BackupService::isDump).toList()) {
				if (file.equals(newest)) {
					continue;
				}
				if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
					Files.deleteIfExists(file);
					Files.deleteIfExists(dir.resolve(file.getFileName().toString().replace(SUFFIX, ".deletion-ledger.txt")));
					removed++;
				}
			}
		}
		catch (IOException ex) {
			log.warn("Could not prune old backups: {}", ex.getMessage());
		}
		return removed;
	}

	Path newestBackup() {
		Path dir = backupDir();
		if (!Files.isDirectory(dir)) {
			return null;
		}
		// Newest by when the file was actually written, not by name.
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(BackupService::isDump)
				.max(Comparator.comparing(BackupService::modified))
				.orElse(null);
		}
		catch (IOException ex) {
			return null;
		}
	}

	Path backupDir() {
		return Path.of(props.backup().dir()).toAbsolutePath().normalize();
	}

	public Path currentBackupDir() {
		return backupDir();
	}

	private static Instant modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toInstant();
		}
		catch (IOException ex) {
			return Instant.EPOCH;
		}
	}

	private static boolean isDump(Path p) {
		String n = p.getFileName().toString();
		return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
	}

	private RunResult record(String kind, Instant started, boolean success, String fileName, Long size, String sha,
			String detail) {
		String safeDetail = (detail == null || detail.length() <= 1000) ? detail : detail.substring(0, 1000);
		jdbc.update("""
				INSERT INTO ops_run (kind, started_at, finished_at, outcome, file_name, size_bytes, sha256, detail)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", kind, started.atOffset(ZoneOffset.UTC), clock.instant().atOffset(ZoneOffset.UTC),
				success ? "SUCCESS" : "FAILED", fileName, size, sha, safeDetail);
		if (success) {
			log.info("{} succeeded: {}", kind, fileName);
		}
		else {
			log.error("{} failed: {}", kind, safeDetail);
		}
		return new RunResult(kind, success, fileName, size, safeDetail);
	}

	private static String sha256(Path file) throws IOException {
		try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
			in.transferTo(java.io.OutputStream.nullOutputStream());
			return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
