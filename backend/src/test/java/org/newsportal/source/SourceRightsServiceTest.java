package org.newsportal.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.newsportal.TestClockConfig.MutableClock;
import org.newsportal.source.RightsDecision.Reason;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SourceRightsServiceTest {

	private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

	@Autowired
	private SourceRightsService service;

	@Autowired
	private SourceReviewRepository reviews;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private EntityManager em;

	@BeforeEach
	void resetClock() {
		clock.set(START);
	}

	@Test
	void newPublisherBlocksEveryUse() {
		NewsSource source = propose();
		for (ContentUse use : ContentUse.values()) {
			assertThat(service.check(source.getId(), use).allowed()).as(use.name()).isFalse();
		}
	}

	@Test
	void unknownSourceIdIsBlocked() {
		assertThat(service.check(-1L, ContentUse.METADATA).reason()).isEqualTo(Reason.SOURCE_NOT_FOUND);
	}

	@Test
	void activePublisherWithoutRecordForAUseBlocksThatUse() {
		NewsSource source = activeSourceWithMetadata();
		assertThat(service.check(source.getId(), ContentUse.METADATA).allowed()).isTrue();
		assertThat(service.check(source.getId(), ContentUse.FULL_TEXT_DISPLAY).reason())
			.isEqualTo(Reason.NO_RIGHTS_RECORD);
	}

	@Test
	void permissionStopsWorkingOnceItExpires() {
		NewsSource source = activeSourceWithMetadata();
		service.recordRightsDecision(source.getId(), ContentUse.FULL_TEXT_DISPLAY,
				permitted(null, START.plus(Duration.ofDays(30))));
		assertThat(service.check(source.getId(), ContentUse.FULL_TEXT_DISPLAY).allowed()).isTrue();

		clock.set(START.plus(Duration.ofDays(30)));

		assertThat(service.check(source.getId(), ContentUse.FULL_TEXT_DISPLAY).reason()).isEqualTo(Reason.EXPIRED);
	}

	@Test
	void unknownAndDisputedDecisionsBlockAPreviouslyPermittedUse() {
		NewsSource source = activeSourceWithMetadata();
		service.recordRightsDecision(source.getId(), ContentUse.NARRATION, permitted(null, null));
		assertThat(service.check(source.getId(), ContentUse.NARRATION).allowed()).isTrue();

		service.recordRightsDecision(source.getId(), ContentUse.NARRATION,
				decision(RightsStatus.DISPUTED, "Publisher disputes narration rights"));
		assertThat(service.check(source.getId(), ContentUse.NARRATION).reason()).isEqualTo(Reason.DISPUTED);

		service.recordRightsDecision(source.getId(), ContentUse.NARRATION,
				decision(RightsStatus.UNKNOWN, "Terms changed; needs re-review"));
		assertThat(service.check(source.getId(), ContentUse.NARRATION).reason()).isEqualTo(Reason.UNKNOWN);
	}

	@Test
	void suspendedUseStaysBlockedUntilRestoredWithApproval() {
		NewsSource source = activeSourceWithMetadata();
		service.recordRightsDecision(source.getId(), ContentUse.VIDEO_EMBED, permitted(null, null));

		service.suspendUse(source.getId(), ContentUse.VIDEO_EMBED, "manager-a", "Embed terms under question");
		assertThat(service.check(source.getId(), ContentUse.VIDEO_EMBED).reason()).isEqualTo(Reason.USE_SUSPENDED);

		// A new rights decision does not silently lift the suspension.
		service.recordRightsDecision(source.getId(), ContentUse.VIDEO_EMBED, permitted(null, null));
		assertThat(service.check(source.getId(), ContentUse.VIDEO_EMBED).reason()).isEqualTo(Reason.USE_SUSPENDED);

		service.restoreUse(source.getId(), ContentUse.VIDEO_EMBED, "manager-b", "Publisher confirmed embeds OK");
		assertThat(service.check(source.getId(), ContentUse.VIDEO_EMBED).allowed()).isTrue();
	}

	@Test
	void restoringAUseThatIsNotSuspendedIsRejected() {
		NewsSource source = activeSourceWithMetadata();
		assertThatThrownBy(() -> service.restoreUse(source.getId(), ContentUse.METADATA, "manager", "ok"))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void suspendingThePublisherBlocksAllUsesAndRestoreBringsThemBack() {
		NewsSource source = activeSourceWithMetadata();
		service.suspendSource(source.getId(), "manager-a", "Feed served unlicensed content");
		assertThat(service.check(source.getId(), ContentUse.METADATA).reason()).isEqualTo(Reason.SOURCE_NOT_ACTIVE);

		service.restoreSource(source.getId(), "manager-b", "Publisher fixed the feed");
		assertThat(service.check(source.getId(), ContentUse.METADATA).allowed()).isTrue();
	}

	@Test
	void publisherCannotBeActivatedWithoutMetadataPermission() {
		NewsSource source = propose();
		assertThatThrownBy(() -> service.activateSource(source.getId(), "manager", "Looks fine"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("METADATA");
	}

	@Test
	void publisherCannotBeRestoredOnceMetadataPermissionHasExpired() {
		NewsSource source = propose();
		service.recordRightsDecision(source.getId(), ContentUse.METADATA, permitted(null, START.plusSeconds(60)));
		service.activateSource(source.getId(), "manager", "Approved for pilot");
		service.suspendSource(source.getId(), "manager", "Outage investigation");

		clock.set(START.plusSeconds(120));

		assertThatThrownBy(() -> service.restoreSource(source.getId(), "manager", "Back online"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("EXPIRED");
	}

	@Test
	void decisionsRequireReviewerAndNotesAndCannotGrantExpiredPermission() {
		NewsSource source = propose();
		Long id = source.getId();
		assertThatThrownBy(() -> service.recordRightsDecision(id, ContentUse.METADATA,
				new RightsReview(RightsStatus.PERMITTED, null, null, null, " ", "notes", null, null)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.recordRightsDecision(id, ContentUse.METADATA,
				new RightsReview(RightsStatus.PERMITTED, null, null, null, "reviewer", "", null, null)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.recordRightsDecision(id, ContentUse.METADATA, permitted(null, START)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("expired");
	}

	@Test
	void everyChangeIsRecordedInReviewHistory() {
		NewsSource source = activeSourceWithMetadata();
		service.suspendUse(source.getId(), ContentUse.METADATA, "manager-a", "Check attribution");
		service.restoreUse(source.getId(), ContentUse.METADATA, "manager-b", "Attribution verified");

		List<ReviewAction> actions = reviews.findBySourceIdOrderByIdAsc(source.getId())
			.stream()
			.map(SourceReview::getAction)
			.toList();
		assertThat(actions).containsExactly(ReviewAction.RIGHTS_DECISION, ReviewAction.SOURCE_ACTIVATED,
				ReviewAction.USE_SUSPENDED, ReviewAction.USE_RESTORED);
		SourceReview first = reviews.findBySourceIdOrderByIdAsc(source.getId()).getFirst();
		assertThat(first.getReviewer()).isEqualTo("reviewer");
		assertThat(first.getNextReviewDue()).isEqualTo(LocalDate.of(2026, 12, 31));
		assertThat(first.getReviewedAt()).isEqualTo(START);
	}

	@Test
	void reviewHistoryCannotBeEditedOrDeleted() {
		NewsSource source = activeSourceWithMetadata();
		em.flush();
		Long reviewId = reviews.findBySourceIdOrderByIdAsc(source.getId()).getFirst().getId();

		assertThatThrownBy(() -> jdbc.update("UPDATE source_review SET notes = 'changed' WHERE id = ?", reviewId))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("append-only");
	}

	@Test
	void databaseRejectsPermissionWithoutAReview() {
		NewsSource source = propose();
		em.flush();
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO source_rights (source_id, content_use, status) VALUES (?, 'FULL_TEXT_DISPLAY', 'PERMITTED')",
				source.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private NewsSource propose() {
		String code = "src-" + UUID.randomUUID().toString().substring(0, 8);
		return service.proposeSource(code, "Example News", "https://news.example.org", "en");
	}

	private NewsSource activeSourceWithMetadata() {
		NewsSource source = propose();
		service.recordRightsDecision(source.getId(), ContentUse.METADATA, permitted(null, null));
		return service.activateSource(source.getId(), "manager", "Passed rights and technical review");
	}

	private static RightsReview permitted(Instant validFrom, Instant expiresAt) {
		return new RightsReview(RightsStatus.PERMITTED, validFrom, expiresAt, null, "reviewer",
				"Licence clause 4 allows this use", "https://news.example.org/terms", LocalDate.of(2026, 12, 31));
	}

	private static RightsReview decision(RightsStatus status, String notes) {
		return new RightsReview(status, null, null, null, "reviewer", notes, null, null);
	}

}
