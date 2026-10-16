package org.newsportal.reader;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.newsportal.TestClockConfig.MutableClock;
import org.newsportal.article.ArticleRepository;
import org.newsportal.ingestion.ArticleUpsertService;
import org.newsportal.ingestion.FeedParser.FeedEntry;
import org.newsportal.source.ContentUse;
import org.newsportal.source.NewsSource;
import org.newsportal.source.RightsReview;
import org.newsportal.source.RightsStatus;
import org.newsportal.source.SourceRightsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OfflineApiTest {

	private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SourceRightsService rights;

	@Autowired
	private ArticleUpsertService upserts;

	@Autowired
	private ArticleRepository articles;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
	}

	@Test
	void fullTextOfflineNeedsDisplayAndStorageAndExpiresWithTheEarliestRight() throws Exception {
		Instant storageEnds = NOW.plus(Duration.ofDays(5));
		NewsSource full = source(storageEnds, ContentUse.FULL_TEXT_DISPLAY, ContentUse.STORAGE);
		NewsSource displayOnly = source(null, ContentUse.FULL_TEXT_DISPLAY);
		long withText = article(full, "<p>Body</p>");
		long noTextProvided = article(full, null);
		long notStorable = article(displayOnly, "<p>Body</p>");

		mvc.perform(get("/api/v1/articles/offline-status").param("ids", withText + "," + noTextProvided + ","
				+ notStorable + ",999999999"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].level").value("FULL_TEXT"))
			.andExpect(jsonPath("$[0].validUntil").value(storageEnds.toString()))
			.andExpect(jsonPath("$[1].level").value("METADATA"))
			.andExpect(jsonPath("$[2].level").value("METADATA"))
			// Never more than 14 days without checking again.
			.andExpect(jsonPath("$[2].validUntil").value(NOW.plus(Duration.ofDays(14)).toString()))
			.andExpect(jsonPath("$[3].id").value(999999999))
			.andExpect(jsonPath("$[3].level").value("NONE"))
			.andExpect(jsonPath("$[3].validUntil").doesNotExist());
	}

	@Test
	void restrictionsReachDevicesOnTheNextCheckButFeedOutagesDoNot() throws Exception {
		NewsSource src = source(null, ContentUse.FULL_TEXT_DISPLAY, ContentUse.STORAGE);
		long id = article(src, "<p>Body</p>");

		// FR-064: a failing feed alone changes nothing.
		jdbc.update("""
				INSERT INTO source_feed (source_id, feed_url, consecutive_failures, enabled)
				VALUES (?, ?, 9, TRUE)
				""", src.getId(), "https://pub.example/feed-" + UUID.randomUUID() + ".xml");
		mvc.perform(get("/api/v1/articles/offline-status").param("ids", String.valueOf(id)))
			.andExpect(jsonPath("$[0].level").value("FULL_TEXT"));

		rights.suspendUse(src.getId(), ContentUse.STORAGE, "reviewer", "Licence under review");
		mvc.perform(get("/api/v1/articles/offline-status").param("ids", String.valueOf(id)))
			.andExpect(jsonPath("$[0].level").value("METADATA"));

		rights.suspendSource(src.getId(), "reviewer", "Publisher asked us to stop");
		mvc.perform(get("/api/v1/articles/offline-status").param("ids", String.valueOf(id)))
			.andExpect(jsonPath("$[0].level").value("NONE"));
	}

	@Test
	void requestsAreBounded() throws Exception {
		String tooMany = LongStream.rangeClosed(1, 201).mapToObj(Long::toString).collect(Collectors.joining(","));
		mvc.perform(get("/api/v1/articles/offline-status").param("ids", tooMany)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/articles/offline-status").param("ids", "abc")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/articles/offline-status")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
	}

	private NewsSource source(Instant expiresAt, ContentUse... extraUses) {
		String code = "off-" + UUID.randomUUID().toString().substring(0, 8);
		NewsSource src = rights.proposeSource(code, "Publisher " + code, "https://pub.example", "en");
		rights.recordRightsDecision(src.getId(), ContentUse.METADATA, permitted(null));
		for (ContentUse use : extraUses) {
			rights.recordRightsDecision(src.getId(), use, permitted(expiresAt));
		}
		return rights.activateSource(src.getId(), "manager", "Approved for test");
	}

	private static RightsReview permitted(Instant expiresAt) {
		return new RightsReview(RightsStatus.PERMITTED, null, expiresAt, null, "reviewer", "Licence allows it", null,
				LocalDate.of(2027, 1, 1));
	}

	private long article(NewsSource src, String contentHtml) {
		String link = "https://pub.example/" + UUID.randomUUID();
		FeedEntry entry = new FeedEntry(null, link, "Offline story", null, contentHtml, null, NOW, null, List.of());
		upserts.upsert(src.getId(), entry, link, "en", contentHtml != null, clock.instant());
		return articles.findBySourceIdAndCanonicalUrl(src.getId(), link).orElseThrow().getId();
	}

}
