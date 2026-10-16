package org.newsportal.source;

/** Uses of publisher content that need their own permission (FR-004). */
public enum ContentUse {
	METADATA,
	FULL_TEXT_DISPLAY,
	STORAGE,
	TRANSLATION,
	SUMMARY,
	NARRATION,
	VIDEO_EMBED,
	GENERATED_MEDIA,
	/** Showing the publisher's lead image, loaded from their server. */
	IMAGE
}
