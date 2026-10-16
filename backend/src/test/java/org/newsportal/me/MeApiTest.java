package org.newsportal.me;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.newsportal.TestMailConfig.CapturingMailGateway;
import org.newsportal.article.ArticleRepository;
import org.newsportal.identity.AccountService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeApiTest {

	// Later than any other test class, so this class's articles are the newest.
	private static final Instant START = Instant.parse("2026-09-01T12:00:00Z");

	private static final String PASSWORD = "reader passphrase here";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AccountService accounts;

	@Autowired
	private SourceRightsService rights;

	@Autowired
	private ArticleUpsertService upserts;

	@Autowired
	private ArticleRepository articles;

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
	}

	@Test
	void onlySignedInReadersHaveMeEndpoints() throws Exception {
		mvc.perform(get("/api/v1/me/preferences")).andExpect(status().isUnauthorized());
		String staffEmail = email();
		accounts.createStaffAccount(staffEmail, PASSWORD, List.of("ANALYST"), null);
		MockHttpSession staff = login(staffEmail);
		mvc.perform(get("/api/v1/me/preferences").session(staff)).andExpect(status().isForbidden());
	}

	// --- preferences ---

	@Test
	void preferencesHaveSeparateLanguagesAndAreValidated() throws Exception {
		MockHttpSession me = reader();
		mvc.perform(get("/api/v1/me/preferences").session(me))
			.andExpect(jsonPath("$.uiLanguage").value("en"))
			.andExpect(jsonPath("$.newsLanguages", empty()))
			.andExpect(jsonPath("$.historyPaused").value(false));

		send(put("/api/v1/me/preferences"), me, """
				{"uiLanguage":"ur","newsLanguages":["ur","en"],"topics":["sports","politics"],
				 "digestLanguage":"hi","historyPaused":false}
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.uiLanguage").value("ur"))
			.andExpect(jsonPath("$.newsLanguages", contains("ur", "en")))
			.andExpect(jsonPath("$.topics", contains("politics", "sports")))
			.andExpect(jsonPath("$.digestLanguage").value("hi"));

		send(put("/api/v1/me/preferences"), me, "{\"uiLanguage\":\"fr\"}").andExpect(status().isBadRequest());
		send(put("/api/v1/me/preferences"), me, "{\"uiLanguage\":\"en\",\"newsLanguages\":[\"de\"]}")
			.andExpect(status().isBadRequest());
		send(put("/api/v1/me/preferences"), me, "{\"uiLanguage\":\"en\",\"topics\":[\"gossip\"]}")
			.andExpect(status().isBadRequest());
	}

	// --- For You ---

	@Test
	void forYouFollowsChosenLanguagesAndTopicsWithOtherLanguagesSeparate() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 6);
		NewsSource src = activeSource();
		store(src, "ur-politics " + tag, "ur", List.of("Politics"), 3);
		store(src, "hi-sports " + tag, "hi", List.of("Sports"), 2);
		store(src, "en-politics " + tag, "en", List.of("Politics"), 1);

		MockHttpSession me = reader();
		send(put("/api/v1/me/preferences"), me,
				"{\"uiLanguage\":\"en\",\"newsLanguages\":[\"ur\",\"hi\"],\"topics\":[\"politics\"]}")
			.andExpect(status().isOk());

		mvc.perform(get("/api/v1/me/for-you").param("size", "50").session(me))
			.andExpect(jsonPath("$.items[*].title", hasItem("ur-politics " + tag)))
			.andExpect(jsonPath("$.items[*].title", not(hasItem("hi-sports " + tag))))
			.andExpect(jsonPath("$.items[*].title", not(hasItem("en-politics " + tag))))
			.andExpect(jsonPath("$.items[*].language", not(hasItem("en"))));

		mvc.perform(get("/api/v1/me/for-you/other-languages").param("size", "10").session(me))
			.andExpect(jsonPath("$.items[*].title", hasItem("en-politics " + tag)))
			.andExpect(jsonPath("$.items[*].language", not(hasItem("ur"))));
	}

	// --- bookmarks ---

	@Test
	void bookmarksArePrivateSearchableAndRespectRights() throws Exception {
		NewsSource src = activeSource();
		long election = store(src, "Election night results", "en", List.of(), 2);
		long weather = store(src, "Monsoon arrives early", "en", List.of(), 1);
		MockHttpSession me = reader();
		MockHttpSession someoneElse = reader();

		send(put("/api/v1/me/bookmarks/" + election), me, "").andExpect(status().isNoContent());
		send(put("/api/v1/me/bookmarks/" + election), me, "").andExpect(status().isNoContent());
		send(put("/api/v1/me/bookmarks/" + weather), me, "").andExpect(status().isNoContent());
		send(put("/api/v1/me/bookmarks/999999999"), me, "").andExpect(status().isNotFound());

		mvc.perform(get("/api/v1/me/bookmarks").session(me))
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].article.publisher.code").value(src.getCode()));
		mvc.perform(get("/api/v1/me/bookmarks").param("q", "elections").session(me))
			.andExpect(jsonPath("$.items[*].article.title", contains("Election night results")));
		mvc.perform(get("/api/v1/me/bookmarks").session(someoneElse)).andExpect(jsonPath("$.items", empty()));
		// Removing someone else's bookmark does nothing to mine.
		send(delete("/api/v1/me/bookmarks/" + election), someoneElse, "").andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/me/bookmarks").session(me)).andExpect(jsonPath("$.items", hasSize(2)));

		rights.suspendSource(src.getId(), "manager", "Rights dispute");
		mvc.perform(get("/api/v1/me/bookmarks").session(me))
			.andExpect(jsonPath("$.items", empty()))
			.andExpect(jsonPath("$.hiddenCount").value(2));

		rights.restoreSource(src.getId(), "manager", "Resolved");
		send(delete("/api/v1/me/bookmarks/" + weather), me, "").andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/me/bookmarks").session(me)).andExpect(jsonPath("$.items", hasSize(1)));
	}

	// --- collections ---

	@Test
	void collectionsArePrivateAndNamedUniquely() throws Exception {
		NewsSource src = activeSource();
		long a = store(src, "Budget explained", "en", List.of(), 2);
		long b = store(src, "Tax changes", "en", List.of(), 1);
		MockHttpSession me = reader();
		MockHttpSession intruder = reader();

		long id = ((Number) JsonPath.read(send(post("/api/v1/me/collections"), me, "{\"name\":\"Economy\"}")
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		send(post("/api/v1/me/collections"), me, "{\"name\":\"  economy \"}").andExpect(status().isConflict());
		send(post("/api/v1/me/collections"), me, "{\"name\":\"\"}").andExpect(status().isBadRequest());

		send(put("/api/v1/me/collections/" + id + "/items/" + a), me, "").andExpect(status().isNoContent());
		send(put("/api/v1/me/collections/" + id + "/items/" + b), me, "").andExpect(status().isNoContent());
		send(patch("/api/v1/me/collections/" + id), me, "{\"name\":\"Money\"}")
			.andExpect(jsonPath("$.name").value("Money"))
			.andExpect(jsonPath("$.itemCount").value(2));
		mvc.perform(get("/api/v1/me/collections/" + id + "/items").session(me))
			.andExpect(jsonPath("$.items[*].article.title", contains("Tax changes", "Budget explained")));

		// Another reader cannot see, change or even confirm the collection exists.
		mvc.perform(get("/api/v1/me/collections/" + id + "/items").session(intruder)).andExpect(status().isNotFound());
		send(put("/api/v1/me/collections/" + id + "/items/" + a), intruder, "").andExpect(status().isNotFound());
		send(delete("/api/v1/me/collections/" + id), intruder, "").andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/me/collections").session(intruder)).andExpect(jsonPath("$", empty()));

		send(delete("/api/v1/me/collections/" + id + "/items/" + a), me, "").andExpect(status().isNoContent());
		send(delete("/api/v1/me/collections/" + id), me, "").andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/me/collections").session(me)).andExpect(jsonPath("$", empty()));
	}

	// --- history ---

	@Test
	void historyCanBeReviewedPausedDeletedAndCleared() throws Exception {
		NewsSource src = activeSource();
		long first = store(src, "First read", "en", List.of(), 2);
		long second = store(src, "Second read", "en", List.of(), 1);
		MockHttpSession me = reader();

		record(me, first, "READ").andExpect(jsonPath("$.recorded").value(true));
		clock.set(START.plusSeconds(60));
		record(me, second, "READ");
		record(me, second, "LISTEN");
		send(put("/api/v1/me/bookmarks/" + first), me, "");

		mvc.perform(get("/api/v1/me/history").session(me)).andExpect(jsonPath("$.items", hasSize(3)))
			.andExpect(jsonPath("$.items[0].article.title").value("Second read"));
		mvc.perform(get("/api/v1/me/history").param("kind", "LISTEN").session(me))
			.andExpect(jsonPath("$.items[*].kind", contains("LISTEN")));

		send(delete("/api/v1/me/history/" + second), me, "").andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/me/history").session(me))
			.andExpect(jsonPath("$.items[*].article.title", contains("First read")));

		send(put("/api/v1/me/preferences"), me, "{\"uiLanguage\":\"en\",\"historyPaused\":true}");
		record(me, second, "READ").andExpect(jsonPath("$.recorded").value(false));
		mvc.perform(get("/api/v1/me/history").session(me)).andExpect(jsonPath("$.paused").value(true))
			.andExpect(jsonPath("$.items", hasSize(1)));

		send(delete("/api/v1/me/history"), me, "").andExpect(jsonPath("$.deleted").value(1));
		mvc.perform(get("/api/v1/me/history").session(me)).andExpect(jsonPath("$.items", empty()));
		// FR-048: clearing history leaves bookmarks alone.
		mvc.perform(get("/api/v1/me/bookmarks").session(me)).andExpect(jsonPath("$.items", hasSize(1)));
		record(me, first, "WATCH").andExpect(status().isBadRequest());
	}

	// --- helpers ---

	private ResultActions record(MockHttpSession me, long articleId, String kind) throws Exception {
		return send(post("/api/v1/me/history"), me, "{\"articleId\":" + articleId + ",\"kind\":\"" + kind + "\"}");
	}

	private NewsSource activeSource() {
		String code = "me-" + UUID.randomUUID().toString().substring(0, 8);
		NewsSource src = rights.proposeSource(code, "Publisher " + code, "https://pub.example", "en");
		rights.recordRightsDecision(src.getId(), ContentUse.METADATA, new RightsReview(RightsStatus.PERMITTED, null,
				null, null, "reviewer", "Licence allows it", null, LocalDate.of(2027, 1, 1)));
		return rights.activateSource(src.getId(), "manager", "Approved");
	}

	private long store(NewsSource src, String title, String language, List<String> categories, int hoursAgo) {
		String link = "https://pub.example/" + UUID.randomUUID();
		FeedEntry entry = new FeedEntry(null, link, title, null, null, null, START.minus(Duration.ofHours(hoursAgo)),
				null, categories);
		upserts.upsert(src.getId(), entry, link, language, false, clock.instant());
		return articles.findBySourceIdAndCanonicalUrl(src.getId(), link).orElseThrow().getId();
	}

	private MockHttpSession reader() throws Exception {
		String email = email();
		send(post("/api/v1/auth/register"), null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"ageConfirmed\":true}");
		String token = CapturingMailGateway.token(mail.await(email, "Confirm your email"));
		send(post("/api/v1/auth/verify-email"), null, "{\"token\":\"" + token + "\"}");
		return login(email);
	}

	private MockHttpSession login(String email) throws Exception {
		return (MockHttpSession) send(post("/api/v1/auth/login"), new MockHttpSession(),
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, MockHttpSession session, String json)
			throws Exception {
		if (session != null) {
			request.session(session);
		}
		return mvc.perform(request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String email() {
		return "r" + UUID.randomUUID().toString().substring(0, 12) + "@example.org";
	}

}
