package org.newsportal.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.newsportal.TestClockConfig.MutableClock;
import org.newsportal.TestMailConfig.CapturingMailGateway;
import org.newsportal.TestTranslatorConfig.FakeTranslator;
import org.newsportal.article.ArticleRepository;
import org.newsportal.identity.RateLimiter;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TranslationApiTest {

	private static final Instant START = Instant.parse("2026-12-01T09:00:00Z");

	private static final String PASSWORD = "translation test passphrase";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private SourceRightsService rights;

	@Autowired
	private ArticleUpsertService upserts;

	@Autowired
	private ArticleRepository articles;

	@Autowired
	private FakeTranslator translator;

	@Autowired
	private CapturingMailGateway mail;

	@Autowired
	private MutableClock clock;

	@Autowired
	private RateLimiter limiter;

	@BeforeEach
	void setUp() {
		clock.set(START);
		limiter.reset();
		translator.available = true;
		translator.fail = false;
	}

	@AfterEach
	void tearDown() {
		translator.available = true;
		translator.fail = false;
	}

	@Test
	void translationsNeedThePublishersPermission() throws Exception {
		Story story = story(false, "Water supply restored");
		MockHttpSession reader = reader();

		mvc.perform(get("/api/v1/articles/" + story.id())).andExpect(jsonPath("$.translationAvailable").value(false));
		translate(story.id(), "hi", reader).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/articles/" + story.id() + "/translations/hi")).andExpect(status().isNotFound());
	}

	@Test
	void readersTranslateOnDemandAndEveryoneReadsTheCachedResult() throws Exception {
		Story story = story(true, "Water supply restored");
		MockHttpSession reader = reader();
		mvc.perform(get("/api/v1/articles/" + story.id())).andExpect(jsonPath("$.translationAvailable").value(true));

		// Guests can read translations but not order new ones (cost control).
		mvc.perform(post("/api/v1/articles/" + story.id() + "/translations/hi").with(csrf()))
			.andExpect(status().isUnauthorized());

		int before = translator.calls.get();
		translate(story.id(), "ur", reader).andExpect(status().isOk())
			.andExpect(jsonPath("$.language").value("ur"))
			.andExpect(jsonPath("$.direction").value("rtl"))
			.andExpect(jsonPath("$.sourceLanguage").value("en"))
			.andExpect(jsonPath("$.title").value("[ur] Water supply restored"))
			.andExpect(jsonPath("$.excerpt").value("[ur] Summary"))
			.andExpect(jsonPath("$.bodyHtml", containsString("<p>Body text</p>")))
			// Model output is cleaned like the original.
			.andExpect(jsonPath("$.bodyHtml", not(containsString("script"))))
			.andExpect(jsonPath("$.machineTranslated").value(true))
			.andExpect(jsonPath("$.model").value("fake-model"));
		translate(story.id(), "ur", reader).andExpect(status().isOk());
		assertThat(translator.calls.get()).isEqualTo(before + 1);

		mvc.perform(get("/api/v1/articles/" + story.id() + "/translations/ur"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("[ur] Water supply restored"));
		mvc.perform(get("/api/v1/articles/" + story.id() + "/translations/hi")).andExpect(status().isNotFound());

		translate(story.id(), "en", reader).andExpect(status().isBadRequest());
		translate(story.id(), "fr", reader).andExpect(status().isBadRequest());
		translate(999_999_999L, "hi", reader).andExpect(status().isNotFound());
	}

	@Test
	void aChangedOriginalOrChangedRightsMakeANewTranslation() throws Exception {
		Story story = story(true, "Bridge opens");
		MockHttpSession reader = reader();
		translate(story.id(), "hi", reader).andExpect(jsonPath("$.bodyHtml", containsString("Body text")));

		// The publisher corrected the headline: the old translation is no longer shown.
		upsert(story.source(), story.link(), "Bridge opens on Monday");
		mvc.perform(get("/api/v1/articles/" + story.id() + "/translations/hi")).andExpect(status().isNotFound());
		translate(story.id(), "hi", reader).andExpect(jsonPath("$.title").value("[hi] Bridge opens on Monday"));

		// Full-text display withdrawn: only title and summary are translated and shown.
		rights.suspendUse(story.source().getId(), ContentUse.FULL_TEXT_DISPLAY, "reviewer", "Under review");
		translate(story.id(), "hi", reader).andExpect(jsonPath("$.bodyHtml").doesNotExist());

		// Translation withdrawn: nothing is shown or made.
		rights.suspendUse(story.source().getId(), ContentUse.TRANSLATION, "reviewer", "Not allowed");
		mvc.perform(get("/api/v1/articles/" + story.id() + "/translations/hi")).andExpect(status().isNotFound());
		translate(story.id(), "hi", reader).andExpect(status().isForbidden());
	}

	@Test
	void failuresAndAMissingKeyAreReportedClearly() throws Exception {
		Story story = story(true, "Market update");
		MockHttpSession reader = reader();
		translator.fail = true;
		translate(story.id(), "ar", reader).andExpect(status().isBadGateway());
		translator.fail = false;
		translator.available = false;
		translate(story.id(), "ar", reader).andExpect(status().isServiceUnavailable());
		mvc.perform(get("/api/v1/articles/" + story.id())).andExpect(jsonPath("$.translationAvailable").value(false));
	}

	@Test
	void newTranslationsAreLimitedPerReaderAndPerDay() throws Exception {
		clock.set(START.plus(Duration.ofDays(30)));
		MockHttpSession first = reader();
		for (int i = 0; i < 3; i++) {
			translate(story(true, "Story " + i).id(), "hi", first).andExpect(status().isOk());
		}
		translate(story(true, "One more").id(), "hi", first).andExpect(status().isTooManyRequests());

		// 5 a day across the app in the test settings.
		MockHttpSession second = reader();
		for (int i = 0; i < 2; i++) {
			translate(story(true, "Second reader " + i).id(), "hi", second).andExpect(status().isOk());
		}
		translate(story(true, "Over the daily limit").id(), "hi", second).andExpect(status().isTooManyRequests());
	}

	// --- helpers ---

	private record Story(long id, NewsSource source, String link) {
	}

	private Story story(boolean translationAllowed, String title) {
		String code = "tr-" + UUID.randomUUID().toString().substring(0, 8);
		NewsSource src = rights.proposeSource(code, "Publisher " + code, "https://pub.example", "en");
		for (ContentUse use : List.of(ContentUse.METADATA, ContentUse.FULL_TEXT_DISPLAY, ContentUse.STORAGE)) {
			rights.recordRightsDecision(src.getId(), use, permitted());
		}
		if (translationAllowed) {
			rights.recordRightsDecision(src.getId(), ContentUse.TRANSLATION, permitted());
		}
		rights.activateSource(src.getId(), "manager", "Approved for test");
		String link = "https://pub.example/" + UUID.randomUUID();
		upsert(src, link, title);
		return new Story(articles.findBySourceIdAndCanonicalUrl(src.getId(), link).orElseThrow().getId(), src, link);
	}

	private void upsert(NewsSource src, String link, String title) {
		upserts.upsert(src.getId(), new FeedEntry(null, link, title, "<p>Summary</p>", "<p>Body text</p>", null,
				clock.instant(), null, List.of()), link, "en", true, clock.instant());
	}

	private static RightsReview permitted() {
		return new RightsReview(RightsStatus.PERMITTED, null, null, null, "reviewer", "Licence allows it", null,
				LocalDate.of(2028, 1, 1));
	}

	private ResultActions translate(long id, String language, MockHttpSession session) throws Exception {
		return mvc.perform(post("/api/v1/articles/" + id + "/translations/" + language).with(csrf()).session(session));
	}

	private MockHttpSession reader() throws Exception {
		String email = "tr-" + UUID.randomUUID() + "@example.org";
		mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"ageConfirmed\":true}"));
		String token = CapturingMailGateway.token(mail.await(email, "Confirm your email"));
		mvc.perform(post("/api/v1/auth/verify-email").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\"}"));
		return (MockHttpSession) mvc
			.perform(post("/api/v1/auth/login").with(csrf()).session(new MockHttpSession())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession();
	}

}
