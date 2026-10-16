package org.newsportal.ops;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs PostgreSQL client tools. Arguments are passed as a list (no shell) and
 * the password goes through the PGPASSWORD environment variable, never the
 * command line.
 */
final class PgTools {

	private final String binDir;

	PgTools(String binDir) {
		this.binDir = binDir;
	}

	/** host, port and database name parsed from a {@code jdbc:postgresql://host:port/db} URL. */
	record Target(String host, int port, String database, String username, String password) {

		static Target of(String jdbcUrl, String username, String password) {
			if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
				throw new IllegalArgumentException("not a PostgreSQL JDBC URL");
			}
			URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
			String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
			if (db.isBlank() || uri.getHost() == null) {
				throw new IllegalArgumentException("JDBC URL must include host and database");
			}
			return new Target(uri.getHost(), uri.getPort() == -1 ? 5432 : uri.getPort(), db, username, password);
		}

		List<String> connectionArgs() {
			return List.of("--host=" + host, "--port=" + port, "--username=" + username, "--dbname=" + database,
					"--no-password");
		}

	}

	record Result(int exitCode, String output) {

		boolean ok() {
			return exitCode == 0;
		}

	}

	Result run(String tool, Target target, List<String> args, long timeoutMinutes) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(executable(tool));
		command.addAll(target == null ? List.of() : target.connectionArgs());
		command.addAll(args);
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		if (target != null) {
			builder.environment().put("PGPASSWORD", target.password() == null ? "" : target.password());
		}
		builder.environment().put("PGCONNECT_TIMEOUT", "10");
		Process process = builder.start();
		// Drain output on another thread so the timeout below still applies if the tool hangs.
		StringBuilder output = new StringBuilder();
		Thread reader = Thread.ofVirtual().start(() -> {
			try (InputStream in = process.getInputStream()) {
				// Keep only the start of the output: enough for diagnosis, bounded in size.
				output.append(new String(in.readNBytes(4000), StandardCharsets.UTF_8));
				in.transferTo(java.io.OutputStream.nullOutputStream());
			}
			catch (IOException ignored) {
				// Process ended or was killed.
			}
		});
		if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
			process.destroyForcibly();
			return new Result(-1, tool + " timed out after " + timeoutMinutes + " minutes");
		}
		reader.join(5000);
		return new Result(process.exitValue(), output.toString().strip());
	}

	private String executable(String tool) {
		String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? tool + ".exe" : tool;
		return binDir.isBlank() ? name : Path.of(binDir, name).toString();
	}

}
