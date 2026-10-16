package org.newsportal.notify;

import java.text.Normalizer;
import java.time.Duration;
import java.util.Locale;

import org.newsportal.notify.NotificationDtos.Frequency;

/**
 * Shared rules for who gets which notification (FR-066, FR-067). SQL
 * fragments expect the reader as {@code u} (app_user).
 */
final class Audience {

	private Audience() {
	}

	/** Readers who can receive notifications at all. */
	static final String ACTIVE_READER = " u.status = 'ACTIVE' AND u.account_type = 'READER' ";

	/**
	 * The reader's notification languages; if none, their news languages; if
	 * none, their interface language. {@code language} is an SQL expression.
	 */
	static String languageMatches(String language) {
		return " " + """
				(CASE WHEN EXISTS (SELECT 1 FROM notification_language nl WHERE nl.user_id = u.id)
				      THEN EXISTS (SELECT 1 FROM notification_language nl WHERE nl.user_id = u.id AND nl.language = %1$s)
				      WHEN EXISTS (SELECT 1 FROM user_language ul WHERE ul.user_id = u.id)
				      THEN EXISTS (SELECT 1 FROM user_language ul WHERE ul.user_id = u.id AND ul.language = %1$s)
				      ELSE u.ui_language = %1$s END)
				""".formatted(language);
	}

	/**
	 * The reader's notification topics; if none, their feed topics; if none,
	 * every topic. {@code articleId} is an SQL expression.
	 */
	static String topicMatches(String articleId) {
		return " " + """
				(CASE WHEN EXISTS (SELECT 1 FROM notification_topic nt WHERE nt.user_id = u.id)
				      THEN EXISTS (SELECT 1 FROM notification_topic nt
				                   JOIN article_category ac ON ac.category_id = nt.category_id
				                   WHERE nt.user_id = u.id AND ac.article_id = %1$s)
				      WHEN EXISTS (SELECT 1 FROM user_topic ut WHERE ut.user_id = u.id)
				      THEN EXISTS (SELECT 1 FROM user_topic ut
				                   JOIN article_category ac ON ac.category_id = ut.category_id
				                   WHERE ut.user_id = u.id AND ac.article_id = %1$s)
				      ELSE TRUE END)
				""".formatted(articleId);
	}

	static Duration period(Frequency frequency) {
		return switch (frequency) {
			case DAILY -> Duration.ofDays(1);
			case WEEKLY -> Duration.ofDays(7);
			case OFF -> throw new IllegalArgumentException("no period when digests are off");
		};
	}

	/**
	 * Headline reduced to letters, marks and digits, so the same story from a
	 * syndication feed is recognised as a repeat (FR-067). Conservative: only
	 * identical wording matches (SRS §16).
	 */
	static String titleKey(String title) {
		String key = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC)
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ")
			.strip();
		return (key.length() <= 1000) ? key : key.substring(0, 1000);
	}

}
