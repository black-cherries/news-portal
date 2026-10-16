package org.newsportal.translate;

/**
 * Machine translation engine. The production implementation is Claude through
 * Spring AI ({@link ClaudeTranslator}); tests use a fake.
 */
public interface Translator {

	/** Whether translation can run at all (for example, an API key is set). */
	boolean available();

	/** The model name recorded with each translation. */
	String model();

	/**
	 * @param bodyHtml sanitized HTML, or null to translate only title and excerpt
	 * @throws TranslationFailedException if the engine fails or answers in an unusable form
	 */
	Result translate(String sourceLanguage, String targetLanguage, String title, String excerpt, String bodyHtml);

	record Result(String title, String excerpt, String bodyHtml) {
	}

	class TranslationFailedException extends RuntimeException {

		public TranslationFailedException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
