package org.newsportal.source;

/** Outcome of the latest rights review for one content use. Only PERMITTED allows the use (FR-005). */
public enum RightsStatus {
	UNKNOWN,
	PERMITTED,
	DENIED,
	DISPUTED
}
