package org.newsportal.ingestion;

/**
 * Result of one feed fetch. Keeps "valid feed, nothing new" apart from the
 * different kinds of failure (FR-015), and rights blocks apart from both (FR-079).
 */
public enum FeedOutcome {

	/** New or changed articles were stored. */
	SUCCESS(false),
	/** Valid feed; nothing new or changed. Not a failure. */
	NO_NEW_ITEMS(false),
	/** Publisher answered 304 Not Modified. Not a failure. */
	NOT_MODIFIED(false),
	/** Not fetched: the source is inactive or METADATA use is not permitted. Not an ingestion failure. */
	BLOCKED_BY_RIGHTS(false),
	AUTH_FAILURE(true),
	RATE_LIMITED(true),
	/** 404/410: feed or publisher page is gone or inaccessible. */
	NOT_FOUND(true),
	HTTP_ERROR(true),
	NETWORK_ERROR(true),
	MALFORMED(true),
	TOO_LARGE(true);

	private final boolean failure;

	FeedOutcome(boolean failure) {
		this.failure = failure;
	}

	public boolean isFailure() {
		return failure;
	}

}
