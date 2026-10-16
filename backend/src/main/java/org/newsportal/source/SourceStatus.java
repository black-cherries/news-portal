package org.newsportal.source;

public enum SourceStatus {
	/** Added but not yet approved; no content use is allowed. */
	PROPOSED,
	ACTIVE,
	SUSPENDED,
	/** No longer used. Records stay for retention purposes. */
	RETIRED
}
