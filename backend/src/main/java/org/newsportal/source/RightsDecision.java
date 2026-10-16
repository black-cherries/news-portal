package org.newsportal.source;

/** Whether a content use is allowed right now, and why. */
public record RightsDecision(boolean allowed, Reason reason) {

	public enum Reason {
		PERMITTED,
		SOURCE_NOT_FOUND,
		SOURCE_NOT_ACTIVE,
		NO_RIGHTS_RECORD,
		USE_SUSPENDED,
		UNKNOWN,
		DENIED,
		DISPUTED,
		NOT_YET_VALID,
		EXPIRED
	}

	static RightsDecision allow() {
		return new RightsDecision(true, Reason.PERMITTED);
	}

	static RightsDecision block(Reason reason) {
		return new RightsDecision(false, reason);
	}

}
