package org.newsportal.source;

import java.time.Instant;

import org.newsportal.source.RightsDecision.Reason;

/**
 * Decides whether a content use is allowed. Anything not positively permitted
 * is blocked: unknown, expired, disputed or suspended uses all fail (FR-005).
 */
public final class RightsPolicy {

	private RightsPolicy() {
	}

	public static RightsDecision evaluate(NewsSource source, SourceRights rights, Instant now) {
		if (source == null) {
			return RightsDecision.block(Reason.SOURCE_NOT_FOUND);
		}
		if (source.getStatus() != SourceStatus.ACTIVE) {
			return RightsDecision.block(Reason.SOURCE_NOT_ACTIVE);
		}
		return evaluateUse(rights, now);
	}

	/** Checks the use-level record only, ignoring the publisher's status. */
	public static RightsDecision evaluateUse(SourceRights rights, Instant now) {
		if (rights == null) {
			return RightsDecision.block(Reason.NO_RIGHTS_RECORD);
		}
		if (rights.isSuspended()) {
			return RightsDecision.block(Reason.USE_SUSPENDED);
		}
		switch (rights.getStatus()) {
			case UNKNOWN:
				return RightsDecision.block(Reason.UNKNOWN);
			case DENIED:
				return RightsDecision.block(Reason.DENIED);
			case DISPUTED:
				return RightsDecision.block(Reason.DISPUTED);
			case PERMITTED:
				break;
		}
		if (rights.getValidFrom() != null && now.isBefore(rights.getValidFrom())) {
			return RightsDecision.block(Reason.NOT_YET_VALID);
		}
		if (rights.getExpiresAt() != null && !now.isBefore(rights.getExpiresAt())) {
			return RightsDecision.block(Reason.EXPIRED);
		}
		return RightsDecision.allow();
	}

}
