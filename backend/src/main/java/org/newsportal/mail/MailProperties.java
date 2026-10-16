package org.newsportal.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code newsportal.mail}.
 *
 * @param mode {@code smtp} (real delivery via spring.mail.*), {@code outbox}
 * (development: write messages to files) or {@code capture} (tests)
 * @param from sender address
 * @param outboxDir where {@code outbox} mode writes messages
 * @param publicBaseUrl frontend address used in links inside emails
 */
@ConfigurationProperties("newsportal.mail")
public record MailProperties(String mode, String from, String outboxDir, String publicBaseUrl) {
}
