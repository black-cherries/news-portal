package org.newsportal.reader;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.newsportal.ingestion.FeedParser.FeedVideo;
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
class VideoApiTest {

	private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

	private static final List<FeedVideo> VIDEOS = List.of(new FeedVideo("https://youtu.be/dQw4w9WgXcQ", null),
			new FeedVideo("https://cdn.example.org/clip.mp4", "video/mp4"),
			new FeedVideo("https://example.org/video-page", "video/x-unknown"));

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
	void videosAreLinksUnlessEmbeddingIsPermitted() throws Exception {
		NewsSource src = source();
		String link = "https://pub.example/" + UUID.randomUUID();
		long id = store(src, link, VIDEOS);

		// No VIDEO_EMBED decision yet: links to the originals only (FR-005, FR-056).
		mvc.perform(get("/api/v1/articles/" + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.videos.length()").value(3))
			.andExpect(jsonPath("$.videos[0].sourceUrl").value("https://youtu.be/dQw4w9WgXcQ"))
			.andExpect(jsonPath("$.videos[0].provider").value("YOUTUBE"))
			.andExpect(jsonPath("$.videos[0].embedUrl").doesNotExist())
			.andExpect(jsonPath("$.videos[1].embedUrl").doesNotExist());

		rights.recordRightsDecision(src.getId(), ContentUse.VIDEO_EMBED, permitted());
		mvc.perform(get("/api/v1/articles/" + id))
			.andExpect(jsonPath("$.videos[0].embedUrl").value("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
			.andExpect(jsonPath("$.videos[1].provider").value("FILE"))
			.andExpect(jsonPath("$.videos[1].embedUrl").value("https://cdn.example.org/clip.mp4"))
			.andExpect(jsonPath("$.videos[1].mimeType").value("video/mp4"))
			// Unknown provider: still only a link.
			.andExpect(jsonPath("$.videos[2].provider").value("LINK"))
			.andExpect(jsonPath("$.videos[2].embedUrl").doesNotExist());

		rights.suspendUse(src.getId(), ContentUse.VIDEO_EMBED, "reviewer", "Embed terms under review");
		mvc.perform(get("/api/v1/articles/" + id)).andExpect(jsonPath("$.videos[0].embedUrl").doesNotExist());

		// The feed changed its videos: the stored list follows.
		store(src, link, List.of(VIDEOS.get(1)));
		mvc.perform(get("/api/v1/articles/" + id))
			.andExpect(jsonPath("$.videos.length()").value(1))
			.andExpect(jsonPath("$.videos[0].provider").value("FILE"));
	}

	private NewsSource source() {
		String code = "vid-" + UUID.randomUUID().toString().substring(0, 8);
		NewsSource src = rights.proposeSource(code, "Publisher " + code, "https://pub.example", "en");
		rights.recordRightsDecision(src.getId(), ContentUse.METADATA, permitted());
		return rights.activateSource(src.getId(), "manager", "Approved for test");
	}

	private static RightsReview permitted() {
		return new RightsReview(RightsStatus.PERMITTED, null, null, null, "reviewer", "Licence allows it", null,
				LocalDate.of(2027, 1, 1));
	}

	private long store(NewsSource src, String link, List<FeedVideo> videos) {
		FeedEntry entry = new FeedEntry(null, link, "Story with video", null, null, null, NOW, null, List.of(), videos);
		upserts.upsert(src.getId(), entry, link, "en", false, clock.instant());
		return articles.findBySourceIdAndCanonicalUrl(src.getId(), link).orElseThrow().getId();
	}

}
