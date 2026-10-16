package org.newsportal.mail;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.newsportal.mail.MailGateway.OutgoingMail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Account and security emails (FR-069). Tokens go in the URL fragment (#) so
 * they are not sent to servers in requests or Referer headers.
 *
 * Messages are English-only for now; localized templates come with the reader UI.
 */
@Component
public class AccountMailer implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(AccountMailer.class);

	private final MailGateway gateway;

	/**
	 * Mail is sent off the request thread, so response time does not reveal
	 * whether an email was sent (FR-042).
	 */
	private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("mail").factory());

	private final String baseUrl;

	public AccountMailer(MailGateway gateway, MailProperties props) {
		this.gateway = gateway;
		this.baseUrl = props.publicBaseUrl().replaceAll("/+$", "");
	}

	@Override
	public void destroy() throws InterruptedException {
		executor.shutdown();
		executor.awaitTermination(10, TimeUnit.SECONDS);
	}

	public void sendVerification(String to, String token, Duration validFor) {
		send(to, "Confirm your email address", """
				Welcome to News Portal.

				Confirm your email address to finish creating your account:
				%s/verify-email#token=%s

				This link works once and expires in %d hours. If you did not sign up, ignore this email.
				""".formatted(baseUrl, token, validFor.toHours()));
	}

	public void sendAccountExists(String to) {
		send(to, "Someone tried to sign up with your email", """
				Someone tried to create a News Portal account with this email address, but you already have one.

				If it was you, sign in at %s/sign-in, or reset your password at %s/forgot-password.
				If it was not you, you can ignore this email. Your account has not changed.
				""".formatted(baseUrl, baseUrl));
	}

	public void sendPasswordReset(String to, String token, Duration validFor) {
		send(to, "Reset your password", """
				We received a request to reset your News Portal password.

				Choose a new password here:
				%s/reset-password#token=%s

				This link works once and expires in %d minutes. If you did not ask for this, ignore this email;
				your password has not changed.
				""".formatted(baseUrl, token, validFor.toMinutes()));
	}

	public void sendStaffInvite(String to, String token, Duration validFor) {
		send(to, "You have been invited to News Portal staff", """
				A News Portal administrator created a staff account for this email address.

				Choose your password to activate it:
				%s/reset-password#token=%s

				This link works once and expires in %d hours. If you were not expecting this, ignore this email.
				""".formatted(baseUrl, token, validFor.toHours()));
	}

	public void sendDeletionScheduled(String to, java.time.Instant dueAt) {
		send(to, "Your account will be deleted", """
				You asked us to delete your News Portal account. You have been signed out everywhere.

				Your account and its data will be permanently deleted after %s (UTC).
				Until then you can change your mind: sign in at %s/sign-in and choose to restore your account.
				""".formatted(dueAt.truncatedTo(java.time.temporal.ChronoUnit.MINUTES), baseUrl));
	}

	public void sendAccountDeleted(String to) {
		send(to, "Your account has been deleted", """
				Your News Portal account and its data have now been deleted. This cannot be undone.

				You can still read News Portal as a guest, or create a new account at any time.
				""");
	}

	/** FR-072: a meaningful progress update. Only the status and the public note are included. */
	public void sendReportUpdate(String to, String reference, String status, String publicNote) {
		String note = (publicNote == null) ? "" : "\nMessage from the News Portal team:\n" + publicNote + "\n";
		send(to, "Your report " + reference + " is now: " + status, """
				Your report %s is now: %s.
				%s
				You can follow your reports after signing in at %s/reports.
				To stop these emails, turn off updates for this report there.
				""".formatted(reference, status, note, baseUrl));
	}

	public void sendPasswordChanged(String to) {
		send(to, "Your password was changed", """
				The password for your News Portal account was just changed, and you were signed out everywhere.

				If you did not do this, reset your password now at %s/forgot-password.
				""".formatted(baseUrl));
	}

	private void send(String to, String subject, String body) {
		executor.execute(() -> {
			try {
				gateway.send(new OutgoingMail(to, subject, body));
			}
			catch (RuntimeException ex) {
				// Never log the body: it may contain a token.
				log.error("Could not send '{}' email: {}", subject, ex.getClass().getSimpleName());
			}
		});
	}

}
