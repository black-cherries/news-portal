package org.newsportal.reader;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImageApiTest {

	private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

	private static final String IMAGE = "https://cdn.example.org/lead.jpg";

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
	void setUp() {
		clock.set(NOW);
	}

	@Test
	void leadImagesAreShownOnlyWithTheImagePermission() throws Exception {
		String code = "img-" + UUID.randomUUID().toString().substring(0, 8);
		NewsSource src = rights.proposeSource(code, "Publisher " + code, "https://pub.example", "en");
		rights.recordRightsDecision(src.getId(), ContentUse.METADATA, permitted());
		rights.activateSource(src.getId(), "manager", "Approved for test");
		String link = "https://pub.example/" + UUID.randomUUID();
		upserts.upsert(src.getId(), new FeedEntry(null, link, "Story with photo", null, null, null, NOW, null,
				List.of(), List.of(), IMAGE), link, "en", false, clock.instant());
		long id = articles.findBySourceIdAndCanonicalUrl(src.getId(), link).orElseThrow().getId();

		mvc.perform(get("/api/v1/articles/" + id)).andExpect(jsonPath("$.imageUrl").doesNotExist());
		mvc.perform(get("/api/v1/articles").param("source", code))
			.andExpect(jsonPath("$.items[0].id").value(id))
			.andExpect(jsonPath("$.items[0].imageUrl").doesNotExist());

		rights.recordRightsDecision(src.getId(), ContentUse.IMAGE, permitted());
		mvc.perform(get("/api/v1/articles/" + id)).andExpect(jsonPath("$.imageUrl").value(IMAGE));
		mvc.perform(get("/api/v1/articles").param("source", code)).andExpect(jsonPath("$.items[0].imageUrl").value(IMAGE));

		rights.suspendUse(src.getId(), ContentUse.IMAGE, "reviewer", "Photo licence ended");
		mvc.perform(get("/api/v1/articles/" + id)).andExpect(jsonPath("$.imageUrl").doesNotExist());
	}

	private static RightsReview permitted() {
		return new RightsReview(RightsStatus.PERMITTED, null, null, null, "reviewer", "Licence allows it", null,
				LocalDate.of(2027, 1, 1));
	}

}
