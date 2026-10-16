package org.newsportal.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.newsportal.identity.PrivacyService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportApiTest {

	private static final Instant START = Instant.parse("2026-10-10T09:00:00Z");

	private static final String PASSWORD = "report test passphrase";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AccountService accounts;

	@Autowired
	private PrivacyService privacy;

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

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		clock.set(START);
		limiter.reset();
	}

	@Test
	void guestsCanReportAndGetAnUnguessableReference() throws Exception {
		send(post("/api/v1/reports"), null, "{\"type\":\"TECHNICAL\",\"description\":\"The page footer overlaps text on my phone.\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.matchesPattern("NP-[2-9A-HJ-NP-Z]{8}")))
			.andExpect(jsonPath("$.status").value("RECEIVED"));
	}

	@Test
	void readersFollowTheirOwnReportsOnly() throws Exception {
		long article = article("Headline with a wrong date");
		MockHttpSession me = reader(email());
		MockHttpSession other = reader(email());

		String reference = JsonPath.read(send(post("/api/v1/reports"), me, """
				{"type":"CORRECTION_REQUEST","articleId":%d,"description":"The publication date shown is a day early.","notifyMe":true}
				""".formatted(article)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
				"$.reference");

		mvc.perform(get("/api/v1/me/reports").session(me))
			.andExpect(jsonPath("$[0].reference").value(reference))
			.andExpect(jsonPath("$[0].articleTitle").value("Headline with a wrong date"))
			.andExpect(jsonPath("$[0].updates[0].status").value("RECEIVED"));
		mvc.perform(get("/api/v1/me/reports").session(other)).andExpect(jsonPath("$", empty()));
		send(put("/api/v1/me/reports/" + reference + "/notify"), other, "{\"notifyMe\":false}").andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/me/reports")).andExpect(status().isUnauthorized());
	}

	@Test
	void reportsAreValidated() throws Exception {
		send(post("/api/v1/reports"), null, "{\"type\":\"TECHNICAL\",\"description\":\"short\"}").andExpect(status().isBadRequest());
		send(post("/api/v1/reports"), null, "{\"type\":\"GOSSIP\",\"description\":\"Long enough description.\"}")
			.andExpect(status().isBadRequest());
		send(post("/api/v1/reports"), null, "{\"description\":\"Long enough description.\"}").andExpect(status().isBadRequest());
		send(post("/api/v1/reports"), null,
				"{\"type\":\"BROKEN_LINK\",\"articleId\":999999999,\"description\":\"Long enough description.\"}")
			.andExpect(status().isBadRequest());
		send(post("/api/v1/reports"), null,
				"{\"type\":\"BROKEN_LINK\",\"pageUrl\":\"javascript:alert(1)\",\"description\":\"Long enough description.\"}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void reportingIsRateLimited() throws Exception {
		for (int i = 0; i < 10; i++) {
			send(post("/api/v1/reports"), null, "{\"type\":\"TECHNICAL\",\"description\":\"Repeated report number " + i + ".\"}")
				.andExpect(status().isCreated());
		}
		send(post("/api/v1/reports"), null, "{\"type\":\"TECHNICAL\",\"description\":\"One report too many.\"}")
			.andExpect(status().isTooManyRequests());
	}

	@Test
	void reviewersHandleReportsAndReadersSeeOnlyPublicUpdates() throws Exception {
		long article = article("Original headline");
		String readerEmail = email();
		MockHttpSession me = reader(readerEmail);
		String reference = JsonPath.read(send(post("/api/v1/reports"), me, """
				{"type":"MISSING_ATTRIBUTION","articleId":%d,"description":"The publisher name is missing on the card.","notifyMe":true}
				""".formatted(article)).andReturn().getResponse().getContentAsString(), "$.reference");
		long id = jdbc.queryForObject("SELECT id FROM report WHERE reference = ?", Long.class, reference);

		MockHttpSession reviewer = staff("CONTENT_REVIEWER");
		mvc.perform(get("/api/v1/admin/reports").param("status", "RECEIVED").session(reviewer))
			.andExpect(jsonPath("$.items[?(@.reference == '" + reference + "')].reporterType").value("READER"))
			.andExpect(jsonPath("$.items[0].reporterEmail").doesNotExist());

		send(post("/api/v1/admin/reports/" + id + "/assign"), reviewer, "{}")
			.andExpect(jsonPath("$.assignedTo").isNumber());
		send(post("/api/v1/admin/reports/" + id + "/status"), reviewer, """
				{"status":"UNDER_REVIEW","publicNote":"We are checking this with the publisher.","internalNote":"Card template bug?"}
				""").andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
		assertThat(mail.await(readerEmail, reference).body())
			.contains("Under review", "We are checking this with the publisher.")
			.doesNotContain("Card template bug");

		send(post("/api/v1/admin/reports/" + id + "/status"), reviewer, "{\"status\":\"RESOLVED\"}")
			.andExpect(status().isBadRequest());
		send(post("/api/v1/admin/reports/" + id + "/status"), reviewer,
				"{\"status\":\"RESOLVED\",\"resolution\":\"FIXED\",\"publicNote\":\"Fixed in the app.\"}")
			.andExpect(jsonPath("$.status").value("RESOLVED"))
			.andExpect(jsonPath("$.history", hasSize(3)));
		mail.await(readerEmail, reference);

		mvc.perform(get("/api/v1/me/reports").session(me))
			.andExpect(jsonPath("$[0].status").value("RESOLVED"))
			.andExpect(jsonPath("$[0].updates[*].status", contains("RECEIVED", "UNDER_REVIEW", "RESOLVED")))
			.andExpect(jsonPath("$[0].updates[1].note").value("We are checking this with the publisher."))
			.andExpect(content(not(containsString("Card template bug"))));

		// FR-073: a report never changes the article.
		assertThat(articles.findById(article).orElseThrow().getTitle()).isEqualTo("Original headline");
	}

	@Test
	void onlyReviewersHandleReportsAndAnalystsSeeCounts() throws Exception {
		MockHttpSession reader = reader(email());
		MockHttpSession manager = staff("SOURCE_MANAGER");
		MockHttpSession analyst = staff("ANALYST");

		mvc.perform(get("/api/v1/admin/reports").session(reader)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/reports").session(manager)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/reports").session(analyst)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/reports/stats").session(analyst)).andExpect(status().isOk())
			.andExpect(jsonPath("$.byStatus").isArray());
	}

	@Test
	void reportsSurviveAccountDeletionWithoutTheReporterLink() throws Exception {
		String readerEmail = email();
		MockHttpSession me = reader(readerEmail);
		String reference = JsonPath.read(send(post("/api/v1/reports"), me,
				"{\"type\":\"LANGUAGE_ERROR\",\"description\":\"Urdu text is cut off on the article page.\"}")
			.andReturn().getResponse().getContentAsString(), "$.reference");

		String export = send(post("/api/v1/me/export"), me, "{\"password\":\"" + PASSWORD + "\"}")
			.andReturn().getResponse().getContentAsString();
		assertThat(export).contains(reference);

		send(post("/api/v1/me/account/delete"), me, "{\"password\":\"" + PASSWORD + "\"}").andExpect(status().isAccepted());
		clock.set(START.plus(Duration.ofDays(8)));
		privacy.purgeDue();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, readerEmail)).isZero();
		assertThat(jdbc.queryForObject("SELECT reporter_user_id FROM report WHERE reference = ?", Long.class, reference)).isNull();
	}

	// --- helpers ---

	private static org.springframework.test.web.servlet.ResultMatcher content(org.hamcrest.Matcher<String> matcher) {
		return org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(matcher);
	}

	private long article(String title) {
		String code = "rp-" + UUID.randomUUID().toString().substring(0, 8);
		NewsSource src = rights.proposeSource(code, "Publisher " + code, "https://pub.example", "en");
		rights.recordRightsDecision(src.getId(), ContentUse.METADATA, new RightsReview(RightsStatus.PERMITTED, null,
				null, null, "reviewer", "Licence allows it", null, LocalDate.of(2027, 1, 1)));
		rights.activateSource(src.getId(), "manager", "Approved");
		String link = "https://pub.example/" + UUID.randomUUID();
		upserts.upsert(src.getId(), new FeedEntry(null, link, title, null, null, null, START, null, List.of()), link,
				"en", false, clock.instant());
		return articles.findBySourceIdAndCanonicalUrl(src.getId(), link).orElseThrow().getId();
	}

	private MockHttpSession staff(String role) throws Exception {
		String email = email();
		accounts.createStaffAccount(email, PASSWORD, List.of(role), null);
		return login(email);
	}

	private MockHttpSession reader(String email) throws Exception {
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
		return "rep" + UUID.randomUUID().toString().substring(0, 10) + "@example.org";
	}

}
