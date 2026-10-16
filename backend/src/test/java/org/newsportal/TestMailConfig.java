package org.newsportal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.newsportal.mail.MailGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Test profile only: keeps sent emails in memory. */
@Configuration
@Profile("test")
public class TestMailConfig {

	@Bean
	CapturingMailGateway capturingMailGateway() {
		return new CapturingMailGateway();
	}

	public static final class CapturingMailGateway implements MailGateway {

		private final List<OutgoingMail> sent = new ArrayList<>();

		@Override
		public synchronized void send(OutgoingMail mail) {
			sent.add(mail);
			notifyAll();
		}

		/** Waits (mail is sent asynchronously) for the next email to {@code to} whose subject contains {@code subject}. */
		public synchronized OutgoingMail await(String to, String subject) throws InterruptedException {
			long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
			while (true) {
				for (OutgoingMail m : sent) {
					if (m.to().equals(to) && m.subject().contains(subject)) {
						sent.remove(m);
						return m;
					}
				}
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					throw new AssertionError("no '" + subject + "' email to " + to);
				}
				wait(Math.max(1, left / 1_000_000));
			}
		}

		/** Waits briefly and returns everything sent to {@code to}. */
		public synchronized List<OutgoingMail> drain(String to) throws InterruptedException {
			wait(300);
			List<OutgoingMail> mine = sent.stream().filter(m -> m.to().equals(to)).toList();
			sent.removeAll(mine);
			return mine;
		}

		public static String token(OutgoingMail mail) {
			Matcher m = Pattern.compile("#token=([A-Za-z0-9_-]+)").matcher(mail.body());
			if (!m.find()) {
				throw new AssertionError("no token in email");
			}
			return m.group(1);
		}

	}

}
