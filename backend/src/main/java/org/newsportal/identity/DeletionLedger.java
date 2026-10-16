package org.newsportal.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ids of purged accounts (FR-055). Written both to the database and to a file
 * outside it: a database restored from an older backup also loses its ledger
 * rows, but the file still lists every deletion so it can be re-applied.
 * Contains no personal data.
 */
@Component
public class DeletionLedger {

	private final JdbcTemplate jdbc;

	private final Path file;

	public DeletionLedger(JdbcTemplate jdbc, PrivacyProperties props) {
		this.jdbc = jdbc;
		this.file = Path.of(props.deletionLedgerFile()).toAbsolutePath().normalize();
	}

	synchronized void record(long userId, Instant purgedAt) {
		jdbc.update("INSERT INTO account_deletion_ledger (user_id, purged_at) VALUES (?, ?) ON CONFLICT DO NOTHING",
				userId, purgedAt.atOffset(ZoneOffset.UTC));
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, userId + "\t" + purgedAt + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not write deletion ledger file", ex);
		}
	}

	/** Every purged id known to the database or the file. */
	synchronized Set<Long> allIds() {
		Set<Long> ids = new TreeSet<>(jdbc.queryForList("SELECT user_id FROM account_deletion_ledger", Long.class));
		if (Files.exists(file)) {
			try {
				for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					String id = line.split("\t", 2)[0].strip();
					if (!id.isEmpty()) {
						ids.add(Long.parseLong(id));
					}
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException("could not read deletion ledger file", ex);
			}
		}
		return ids;
	}

	Path file() {
		return file;
	}

	static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
