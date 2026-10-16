package org.newsportal.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ClaudeTranslatorTest {

	@Test
	void isOffWithoutAnApiKey() {
		ClaudeTranslator translator = new ClaudeTranslator(
				new TranslationProperties("", "claude-sonnet-5-5", Duration.ofSeconds(5), 1, 1, 100),
				JsonMapper.builder().build());
		assertThat(translator.available()).isFalse();
		assertThatThrownBy(() -> translator.translate("en", "hi", "t", null, null))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void findsTheJsonObjectInsideACodeFence() {
		assertThat(ClaudeTranslator.jsonObject("```json\n{\"title\":\"x\"}\n```")).isEqualTo("{\"title\":\"x\"}");
		assertThat(ClaudeTranslator.jsonObject("{\"title\":\"x\"}")).isEqualTo("{\"title\":\"x\"}");
	}

}
