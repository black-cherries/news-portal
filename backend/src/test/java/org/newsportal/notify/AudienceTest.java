package org.newsportal.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.newsportal.notify.NotificationMailer.Item;

class AudienceTest {

	@Test
	void titleKeyIgnoresCasePunctuationAndSpacingButKeepsMarks() {
		assertThat(Audience.titleKey("  Flood: WARNING — issued! ")).isEqualTo("flood warning issued");
		assertThat(Audience.titleKey("बाढ़ की चेतावनी!")).isEqualTo("बाढ़ की चेतावनी");
		assertThat(Audience.titleKey("بارش، سیلاب")).isEqualTo("بارش سیلاب");
		// Different wording is a different story; matching stays conservative.
		assertThat(Audience.titleKey("Flood warning issued")).isNotEqualTo(Audience.titleKey("Flood warnings issued"));
	}

	@Test
	void repeatedHeadlinesAreListedOnceWithTheOtherPublishers() {
		List<Item> merged = NotificationSender.mergeRepeats(List.of(item(1, "Flood warning", "A"),
				item(2, "Rates rise", "B"), item(3, "Flood warning!", "C"), item(4, "flood  warning", "A"),
				item(5, "Flood warning", "D"), item(6, "Third story", "E")), 2);
		assertThat(merged).extracting(Item::articleId).containsExactly(1L, 2L);
		assertThat(merged.getFirst().alsoFrom()).containsExactly("C", "D");
		assertThat(merged.get(1).alsoFrom()).isEmpty();
	}

	private static Item item(long id, String title, String source) {
		return new Item(id, title, "en", source, List.of());
	}

}
