package org.newsportal.ingestion;

/** Keeps stored diagnostics short and single-line (FR-010: limited details only). */
final class Diagnostics {

	static final int MAX = 500;

	private Diagnostics() {
	}

	static String limit(String text) {
		return (text == null) ? null : truncate(text.replaceAll("\\s+", " ").strip(), MAX);
	}

	static String truncate(String text, int max) {
		if (text == null || text.length() <= max) {
			return text;
		}
		return text.substring(0, max - 1) + "…";
	}

}
