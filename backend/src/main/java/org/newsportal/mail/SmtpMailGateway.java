package org.newsportal.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Real delivery through the SMTP server configured in {@code spring.mail.*}. */
@Component
@ConditionalOnProperty(name = "newsportal.mail.mode", havingValue = "smtp")
public class SmtpMailGateway implements MailGateway {

	private final JavaMailSender sender;

	private final MailProperties props;

	public SmtpMailGateway(JavaMailSender sender, MailProperties props) {
		this.sender = sender;
		this.props = props;
	}

	@Override
	public void send(OutgoingMail mail) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(props.from());
		message.setTo(mail.to());
		message.setSubject(mail.subject());
		message.setText(mail.body());
		sender.send(message);
	}

}
