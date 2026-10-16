package org.newsportal.reader;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Guest reader API against the test database. Each test uses its own publishers. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReaderApiTest {

	private static final Instant START = Instant.parse("2026-03-01T12:00:00Z");

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

	@BeforeEach
	void resetClock() {
		clock.set(START);
	}

	@Test
	void guestSeesLatestArticlesNewestFirstWithAttribution() throws Exception {
		NewsSource src = activeSource("en", null);
		store(src, "Older story", null, "en", hoursAgo(5), List.of());
		store(src, "Newer story", "<p>Summary <b>text</b></p>", "en", hoursAgo(1), List.of());

		mvc.perform(get("/api/v1/articles").param("source", src.getCode()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].title", contains("Newer story", "Older story")))
			.andExpect(jsonPath("$.items[0].excerpt").value("Summary text"))
			.andExpect(jsonPath("$.items[0].publisher.code").value(src.getCode()))
			.andExpect(jsonPath("$.items[0].publisher.name").value("Publisher " + src.getCode()))
			.andExpect(jsonPath("$.items[0].sourceUrl").value(org.hamcrest.Matchers.startsWith("https://")))
			.andExpect(jsonPath("$.items[0].publishedAt").value(hoursAgo(1).toString()))
			.andExpect(jsonPath("$.items[0].ingestedAt").value(START.toString()))
			.andExpect(jsonPath("$.items[0].direction").value("ltr"))
			.andExpect(jsonPath("$.nextCursor").value(nullValue()));
	}

	@Test
	void articlesFromBlockedPublishersAreHidden() throws Exception {
		NewsSource src = activeSource("en", START.plus(Duration.ofDays(1)));
		long id = store(src, "Soon hidden", null, "en", hoursAgo(1), List.of());

		mvc.perform(get("/api/v1/articles").param("source", src.getCode())).andExpect(jsonPath("$.items", hasSize(1)));
		mvc.perform(get("/api/v1/articles/" + id)).andExpect(status().isOk());

		clock.set(START.plus(Duration.ofDays(1)));

		mvc.perform(get("/api/v1/articles").param("source", src.getCode())).andExpect(jsonPath("$.items", hasSize(0)));
		mvc.perform(get("/api/v1/articles/" + id)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/publishers")).andExpect(jsonPath("$[*].code", not(org.hamcrest.Matchers.hasItem(src.getCode()))));
	}

	@Test
	void suspendedPublisherIsHidden() throws Exception {
		NewsSource src = activeSource("en", null);
		long id = store(src, "Story", null, "en", hoursAgo(1), List.of());

		rights.suspendSource(src.getId(), "manager", "Investigating complaint");

		mvc.perform(get("/api/v1/articles").param("source", src.getCode())).andExpect(jsonPath("$.items", hasSize(0)));
		mvc.perform(get("/api/v1/articles/" + id)).andExpect(status().isNotFound());
	}

	@Test
	void filtersByLanguageCategoryAndDate() throws Exception {
		NewsSource src = activeSource("en", null);
		store(src, "Poll results", null, "en", Instant.parse("2026-02-10T09:00:00Z"), List.of("Politics"));
		store(src, "Match report", null, "en", Instant.parse("2026-02-20T09:00:00Z"), List.of("Cricket"));
		store(src, "انتخابات", null, "ur", Instant.parse("2026-02-21T09:00:00Z"), List.of("Politics"));

		mvc.perform(get("/api/v1/articles").param("source", src.getCode()).param("category", "politics"))
			.andExpect(jsonPath("$.items[*].title", contains("انتخابات", "Poll results")));
		mvc.perform(get("/api/v1/articles").param("source", src.getCode()).param("category", "sports"))
			.andExpect(jsonPath("$.items[*].title", contains("Match report")));
		mvc.perform(get("/api/v1/articles").param("source", src.getCode()).param("lang", "ur"))
			.andExpect(jsonPath("$.items[*].title", contains("انتخابات")))
			.andExpect(jsonPath("$.items[0].direction").value("rtl"));
		mvc.perform(get("/api/v1/articles").param("source", src.getCode()).param("lang", "en,ur")
			.param("from", "2026-02-11").param("to", "2026-02-20"))
			.andExpect(jsonPath("$.items[*].title", contains("Match report")));
	}

	@Test
	void searchMatchesStemmedAndExactWordsInEachLanguage() throws Exception {
		NewsSource src = activeSource("en", null);
		store(src, "Parliament holds election", "Voting begins today", "en", hoursAgo(4), List.of());
		store(src, "الأخبار العاجلة", null, "ar", hoursAgo(3), List.of());
		store(src, "پاکستان کی تازہ خبریں", null, "ur", hoursAgo(2), List.of());
		store(src, "भारत में चुनाव", null, "hi", hoursAgo(1), List.of());

		search(src, "elections").andExpect(jsonPath("$.items[*].title", contains("Parliament holds election")));
		search(src, "voting").andExpect(jsonPath("$.items[*].title", contains("Parliament holds election")));
		search(src, "الأخبار").andExpect(jsonPath("$.items[*].title", contains("الأخبار العاجلة")));
		search(src, "پاکستان").andExpect(jsonPath("$.items[*].title", contains("پاکستان کی تازہ خبریں")));
		search(src, "चुनाव").andExpect(jsonPath("$.items[*].title", contains("भारत में चुनाव")));
		search(src, "nothing-matches-this").andExpect(jsonPath("$.items", hasSize(0)));
	}

	@Test
	void cursorPaginationWalksThroughAllItemsWithoutOverlap() throws Exception {
		NewsSource src = activeSource("en", null);
		for (int i = 1; i <= 5; i++) {
			store(src, "Story " + i, null, "en", hoursAgo(10 - i), List.of());
		}

		String first = mvc.perform(get("/api/v1/articles").param("source", src.getCode()).param("size", "2"))
			.andExpect(jsonPath("$.items[*].title", contains("Story 5", "Story 4")))
			.andReturn().getResponse().getContentAsString();
		String cursor = JsonPath.read(first, "$.nextCursor");

		String second = mvc.perform(get("/api/v1/articles").param("source", src.getCode()).param("size", "2")
			.param("cursor", cursor))
			.andExpect(jsonPath("$.items[*].title", contains("Story 3", "Story 2")))
			.andReturn().getResponse().getContentAsString();

		mvc.perform(get("/api/v1/articles").param("source", src.getCode()).param("size", "2")
			.param("cursor", (String) JsonPath.read(second, "$.nextCursor")))
			.andExpect(jsonPath("$.items[*].title", contains("Story 1")))
			.andExpect(jsonPath("$.nextCursor").value(nullValue()));
	}

	@Test
	void futureDatedArticlesDoNotJumpAheadOfNewerOnes() throws Exception {
		NewsSource src = activeSource("en", null);
		store(src, "Claims to be from next year", null, "en", START.plus(Duration.ofDays(365)), List.of());
		clock.set(START.plus(Duration.ofHours(1)));
		store(src, "Genuinely newer", null, "en", START.plus(Duration.ofMinutes(50)), List.of());

		mvc.perform(get("/api/v1/articles").param("source", src.getCode()))
			.andExpect(jsonPath("$.items[*].title", contains("Genuinely newer", "Claims to be from next year")))
			// The publisher's claimed time is still reported as-is.
			.andExpect(jsonPath("$.items[1].publishedAt").value(START.plus(Duration.ofDays(365)).toString()));
	}

	@Test
	void detailShowsTimestampsSeparatelyAndUnknownStaysNull() throws Exception {
		NewsSource src = activeSource("ur", null);
		long id = store(src, "تازہ خبر", null, "ur", null, List.of("Politics"));

		mvc.perform(get("/api/v1/articles/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.publishedAt").value(nullValue()))
			.andExpect(jsonPath("$.publisherUpdatedAt").value(nullValue()))
			.andExpect(jsonPath("$.ingestedAt").value(START.toString()))
			.andExpect(jsonPath("$.direction").value("rtl"))
			.andExpect(jsonPath("$.categories", contains("politics")))
			.andExpect(jsonPath("$.publisherCategories", contains("Politics")))
			.andExpect(jsonPath("$.fullTextStatus").value("NOT_PERMITTED"))
			.andExpect(jsonPath("$.fullTextHtml").value(nullValue()));
	}

	@Test
	void fullTextIsShownOnlyWhenDisplayIsPermitted() throws Exception {
		NewsSource src = activeSource("en", null);
		rights.recordRightsDecision(src.getId(), ContentUse.STORAGE, permitted(null));
		long id = storeWithContent(src, "Full story", "<p>Whole article</p>");

		mvc.perform(get("/api/v1/articles/" + id)).andExpect(jsonPath("$.fullTextStatus").value("NOT_PERMITTED"))
			.andExpect(jsonPath("$.fullTextHtml").value(nullValue()));

		rights.recordRightsDecision(src.getId(), ContentUse.FULL_TEXT_DISPLAY, permitted(null));
		mvc.perform(get("/api/v1/articles/" + id)).andExpect(jsonPath("$.fullTextStatus").value("AVAILABLE"))
			.andExpect(jsonPath("$.fullTextHtml").value("<p>Whole article</p>"))
			.andExpect(jsonPath("$.fullTextNarrationPermitted").value(false));

		rights.recordRightsDecision(src.getId(), ContentUse.NARRATION, permitted(null));
		mvc.perform(get("/api/v1/articles/" + id)).andExpect(jsonPath("$.fullTextNarrationPermitted").value(true));

		long noBody = store(src, "Headline only", null, "en", hoursAgo(1), List.of());
		mvc.perform(get("/api/v1/articles/" + noBody)).andExpect(jsonPath("$.fullTextStatus").value("NOT_PROVIDED"));
	}

	@Test
	void unknownArticleIs404() throws Exception {
		mvc.perform(get("/api/v1/articles/999999999")).andExpect(status().isNotFound());
	}

	@Test
	void invalidParametersAreRejected() throws Exception {
		mvc.perform(get("/api/v1/articles").param("size", "500")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/articles").param("lang", "EN!!")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/articles").param("cursor", "not-a-cursor")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/articles").param("q", "x".repeat(201))).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/articles").param("from", "yesterday")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/articles").param("from", "2026-02-02").param("to", "2026-02-01"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/articles/abc")).andExpect(status().isBadRequest());
	}

	@Test
	void categoriesAndPublishersAreListed() throws Exception {
		NewsSource src = activeSource("hi", null);
		mvc.perform(get("/api/v1/categories")).andExpect(jsonPath("$", hasSize(12)))
			.andExpect(jsonPath("$[0].code").value("world"));
		mvc.perform(get("/api/v1/publishers"))
			.andExpect(jsonPath("$[*].code", org.hamcrest.Matchers.hasItem(src.getCode())));
		NewsSource proposed = rights.proposeSource(code(), "Not approved", "https://p.example", "en");
		mvc.perform(get("/api/v1/publishers"))
			.andExpect(jsonPath("$[*].code", not(org.hamcrest.Matchers.hasItem(proposed.getCode()))));
	}

	// --- helpers ---

	private org.springframework.test.web.servlet.ResultActions search(NewsSource src, String q) throws Exception {
		return mvc.perform(get("/api/v1/articles").param("source", src.getCode()).param("q", q))
			.andExpect(status().isOk());
	}

	private long store(NewsSource src, String title, String descriptionHtml, String language, Instant publishedAt,
			List<String> categories) {
		String link = "https://pub.example/" + UUID.randomUUID();
		FeedEntry entry = new FeedEntry(null, link, title, descriptionHtml, null, null, publishedAt, null, categories);
		upserts.upsert(src.getId(), entry, link, language, false, clock.instant());
		return articles.findBySourceIdAndCanonicalUrl(src.getId(), link).orElseThrow().getId();
	}

	private long storeWithContent(NewsSource src, String title, String contentHtml) {
		String link = "https://pub.example/" + UUID.randomUUID();
		FeedEntry entry = new FeedEntry(null, link, title, null, contentHtml, null, hoursAgo(1), null, List.of());
		upserts.upsert(src.getId(), entry, link, "en", true, clock.instant());
		return articles.findBySourceIdAndCanonicalUrl(src.getId(), link).orElseThrow().getId();
	}

	private NewsSource activeSource(String language, Instant metadataExpiresAt) {
		String code = code();
		NewsSource src = rights.proposeSource(code, "Publisher " + code, "https://pub.example", language);
		rights.recordRightsDecision(src.getId(), ContentUse.METADATA, permitted(metadataExpiresAt));
		return rights.activateSource(src.getId(), "manager", "Approved for test");
	}

	private static RightsReview permitted(Instant expiresAt) {
		return new RightsReview(RightsStatus.PERMITTED, null, expiresAt, null, "reviewer", "Licence allows it", null,
				LocalDate.of(2026, 12, 31));
	}

	private static Instant hoursAgo(int hours) {
		return START.minus(Duration.ofHours(hours));
	}

	private static String code() {
		return "src-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
