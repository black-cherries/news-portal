package org.newsportal;

import java.util.concurrent.atomic.AtomicInteger;

import org.newsportal.translate.Translator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Test profile only: a predictable translator, so tests never call a real model. */
@Configuration
@Profile("test")
public class TestTranslatorConfig {

	@Bean
	@Primary
	FakeTranslator fakeTranslator() {
		return new FakeTranslator();
	}

	public static final class FakeTranslator implements Translator {

		public final AtomicInteger calls = new AtomicInteger();

		public volatile boolean available = true;

		public volatile boolean fail = false;

		@Override
		public boolean available() {
			return available;
		}

		@Override
		public String model() {
			return "fake-model";
		}

		/** "[hi] Title"; the body gets a script tag that must be removed before display. */
		@Override
		public Result translate(String sourceLanguage, String targetLanguage, String title, String excerpt,
				String bodyHtml) {
			calls.incrementAndGet();
			if (fail) {
				throw new TranslationFailedException("fake failure", null);
			}
			String tag = "[" + targetLanguage + "] ";
			return new Result(tag + title, excerpt == null ? null : tag + excerpt,
					bodyHtml == null ? null : "<p>" + tag + "</p>" + bodyHtml + "<script>alert(1)</script>");
		}

	}

}
