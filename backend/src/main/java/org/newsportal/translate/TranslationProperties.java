package org.newsportal.translate;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code newsportal.translation}.
 *
 * @param apiKey Anthropic API key; translation is off when empty
 * @param model Claude model used for translation
 * @param timeout per request
 * @param maxPerDay new translations allowed in 24 hours across the app (cost limit)
 * @param maxPerReaderPerHour new translations one reader can request in an hour
 * @param maxBodyChars longer article bodies are not translated; title and summary still are
 */
@ConfigurationProperties("newsportal.translation")
public record TranslationProperties(String apiKey, String model, Duration timeout, int maxPerDay,
		int maxPerReaderPerHour, int maxBodyChars) {
}
