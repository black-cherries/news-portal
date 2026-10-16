package org.newsportal.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.newsportal.source.RightsDecision.Reason;

class RightsPolicyTest {

	private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

	@Test
	void missingSourceIsBlocked() {
		assertThat(RightsPolicy.evaluate(null, null, NOW).reason()).isEqualTo(Reason.SOURCE_NOT_FOUND);
	}

	@Test
	void sourceThatIsNotActiveBlocksEvenPermittedUses() {
		SourceRights permitted = rights(RightsStatus.PERMITTED, null, null);
		for (SourceStatus status : new SourceStatus[] { SourceStatus.PROPOSED, SourceStatus.SUSPENDED,
				SourceStatus.RETIRED }) {
			RightsDecision decision = RightsPolicy.evaluate(source(status), permitted, NOW);
			assertThat(decision.allowed()).as(status.name()).isFalse();
			assertThat(decision.reason()).isEqualTo(Reason.SOURCE_NOT_ACTIVE);
		}
	}

	@Test
	void missingRightsRecordIsTreatedAsUnknownAndBlocked() {
		RightsDecision decision = RightsPolicy.evaluate(activeSource(), null, NOW);
		assertThat(decision.allowed()).isFalse();
		assertThat(decision.reason()).isEqualTo(Reason.NO_RIGHTS_RECORD);
	}

	@Test
	void unknownDeniedAndDisputedStatusesAreBlocked() {
		assertThat(evaluate(rights(RightsStatus.UNKNOWN, null, null)).reason()).isEqualTo(Reason.UNKNOWN);
		assertThat(evaluate(rights(RightsStatus.DENIED, null, null)).reason()).isEqualTo(Reason.DENIED);
		assertThat(evaluate(rights(RightsStatus.DISPUTED, null, null)).reason()).isEqualTo(Reason.DISPUTED);
	}

	@Test
	void permittedWithinValidityWindowIsAllowed() {
		RightsDecision decision = evaluate(
				rights(RightsStatus.PERMITTED, NOW.minusSeconds(3600), NOW.plusSeconds(3600)));
		assertThat(decision.allowed()).isTrue();
		assertThat(decision.reason()).isEqualTo(Reason.PERMITTED);
	}

	@Test
	void permittedWithoutDatesIsAllowed() {
		assertThat(evaluate(rights(RightsStatus.PERMITTED, null, null)).allowed()).isTrue();
	}

	@Test
	void permissionIsBlockedAtAndAfterExpiry() {
		assertThat(evaluate(rights(RightsStatus.PERMITTED, null, NOW)).reason()).isEqualTo(Reason.EXPIRED);
		assertThat(evaluate(rights(RightsStatus.PERMITTED, null, NOW.minusSeconds(1))).reason())
			.isEqualTo(Reason.EXPIRED);
	}

	@Test
	void permissionIsBlockedBeforeItStarts() {
		assertThat(evaluate(rights(RightsStatus.PERMITTED, NOW.plusSeconds(1), null)).reason())
			.isEqualTo(Reason.NOT_YET_VALID);
	}

	@Test
	void suspensionOverridesPermission() {
		SourceRights record = rights(RightsStatus.PERMITTED, null, null);
		record.suspend(NOW, "publisher complaint", null);
		assertThat(evaluate(record).reason()).isEqualTo(Reason.USE_SUSPENDED);
	}

	private static RightsDecision evaluate(SourceRights record) {
		return RightsPolicy.evaluate(activeSource(), record, NOW);
	}

	private static SourceRights rights(RightsStatus status, Instant validFrom, Instant expiresAt) {
		SourceRights record = new SourceRights(activeSource(), ContentUse.FULL_TEXT_DISPLAY);
		record.applyDecision(status, validFrom, expiresAt, null, null);
		return record;
	}

	private static NewsSource activeSource() {
		return source(SourceStatus.ACTIVE);
	}

	private static NewsSource source(SourceStatus status) {
		NewsSource source = new NewsSource("test", "Test", "https://example.org", "en");
		source.changeStatus(status, null);
		return source;
	}

}
