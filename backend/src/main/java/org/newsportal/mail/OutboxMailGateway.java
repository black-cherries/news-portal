package org.newsportal.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development only: writes each email to a file instead of sending it. The
 * files contain live tokens, so the folder is git-ignored and the pilot
 * profile refuses this mode.
 */
@Component
@ConditionalOnProperty(name = "newsportal.mail.mode", havingValue = "outbox")
public class OutboxMailGateway implements MailGateway {

	private static final Logger log = LoggerFactory.getLogger(OutboxMailGateway.class);

	private final Path dir;

	public OutboxMailGateway(MailProperties props) {
		this.dir = Path.of(props.outboxDir()).toAbsolutePath().normalize();
	}

	@Override
	public void send(OutgoingMail mail) {
		try {
			Files.createDirectories(dir);
			String name = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID() + ".txt";
			String text = "To: " + mail.to() + "\nSubject: " + mail.subject() + "\n\n" + mail.body() + "\n";
			Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
			log.info("Email written to outbox folder {}", dir);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
