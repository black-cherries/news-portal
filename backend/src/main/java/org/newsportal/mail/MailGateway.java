package org.newsportal.mail;

/** Delivers one plain-text email. */
public interface MailGateway {

	void send(OutgoingMail mail);

	record OutgoingMail(String to, String subject, String body) {
	}

}
