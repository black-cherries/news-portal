package org.newsportal.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.newsportal.TestClockConfig.MutableClock;
import org.newsportal.TestMailConfig.CapturingMailGateway;
import org.newsportal.article.ArticleRepository;
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
class PrivacyApiTest {

	private static final Instant START = Instant.parse("2026-08-01T10:00:00Z");

	private static final String PASSWORD = "private reader passphrase";

	@Autowired
	private MockMvc mvc;

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

	// --- export (FR-049) ---

	@Test
	void exportContainsOnlyMyDataAndNoSecrets() throws Exception {
		long article = article("Exported headline");
		String email = email();
		MockHttpSession me = reader(email);
		MockHttpSession other = reader(email());
		send(put("/api/v1/me/bookmarks/" + article), me, "");
		send(post("/api/v1/me/collections"), me, "{\"name\":\"Mine\"}");
		send(post("/api/v1/me/history"), me, "{\"articleId\":" + article + ",\"kind\":\"READ\"}");
		send(post("/api/v1/me/collections"), other, "{\"name\":\"Someone else's\"}");

		send(post("/api/v1/me/export"), me, "{\"password\":\"wrong password\"}").andExpect(status().isBadRequest());

		String json = send(post("/api/v1/me/export"), me, "{\"password\":\"" + PASSWORD + "\"}")
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", containsString("attachment")))
			.andExpect(header().string("Cache-Control", containsString("no-store")))
			.andExpect(jsonPath("$.account.email").value(email))
			.andExpect(jsonPath("$.bookmarks[0].title").value("Exported headline"))
			.andExpect(jsonPath("$.collections", hasSize(1)))
			.andExpect(jsonPath("$.collections[0].name").value("Mine"))
			.andExpect(jsonPath("$.history[0].kind").value("READ"))
			.andReturn().getResponse().getContentAsString();

		assertThat(json).doesNotContain("Someone else's", "argon2", "password_hash", "security_stamp", "token");
	}

	// --- deletion (FR-052..FR-055) ---

	@Test
	void deletionSignsOutAtOnceAndCanBeRestoredDuringTheRecoveryPeriod() throws Exception {
		long article = article("Kept while pending");
		String email = email();
		MockHttpSession me = reader(email);
		MockHttpSession otherDevice = login(email);
		send(put("/api/v1/me/bookmarks/" + article), me, "");

		send(post("/api/v1/me/account/delete"), me, "{\"password\":\"nope nope nope\"}")
			.andExpect(status().isBadRequest());
		send(post("/api/v1/me/account/delete"), me, "{\"password\":\"" + PASSWORD + "\"}")
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.deletionDueAt").value(START.plus(Duration.ofDays(7)).toString()));
		mail.await(email, "will be deleted");

		mvc.perform(get("/api/v1/me/bookmarks").session(me)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me/bookmarks").session(otherDevice)).andExpect(status().isUnauthorized());
		loginRequest(email, PASSWORD).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_DELETION_PENDING"));
		loginRequest(email, "wrong password!").andExpect(status().isUnauthorized());

		// Not purged before the period ends.
		clock.set(START.plus(Duration.ofDays(6)));
		privacy.purgeDue();

		send(post("/api/v1/auth/account/restore"), null, "{\"email\":\"" + email + "\",\"password\":\"wrong one!!\"}")
			.andExpect(status().isUnauthorized());
		send(post("/api/v1/auth/account/restore"), null, "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
			.andExpect(status().isNoContent());
		MockHttpSession back = login(email);
		mvc.perform(get("/api/v1/me/bookmarks").session(back)).andExpect(jsonPath("$.items", hasSize(1)));
	}

	@Test
	void afterTheRecoveryPeriodTheAccountAndItsDataAreGone() throws Exception {
		long article = article("Will vanish");
		String email = email();
		MockHttpSession me = reader(email);
		send(put("/api/v1/me/bookmarks/" + article), me, "");
		send(post("/api/v1/me/collections"), me, "{\"name\":\"Gone soon\"}");
		long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);

		send(post("/api/v1/me/account/delete"), me, "{\"password\":\"" + PASSWORD + "\"}");
		clock.set(START.plus(Duration.ofDays(7)));
		assertThat(privacy.purgeDue()).isGreaterThanOrEqualTo(1);
		mail.await(email, "has been deleted");

		assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", userId)).isZero();
		assertThat(count("SELECT count(*) FROM bookmark WHERE user_id = ?", userId)).isZero();
		assertThat(count("SELECT count(*) FROM collection WHERE user_id = ?", userId)).isZero();
		assertThat(count("SELECT count(*) FROM account_deletion_ledger WHERE user_id = ?", userId)).isOne();
		assertThat(Files.readString(Path.of("target/test-deletion-ledger.txt"))).contains(userId + "\t");
		// The article itself belongs to the publisher and stays.
		assertThat(articles.existsById(article)).isTrue();

		loginRequest(email, PASSWORD).andExpect(status().isUnauthorized());
		// The address can be used for a brand-new account.
		reader(email);
	}

	@Test
	void deletionsAreReappliedAfterABackupRestore() throws Exception {
		String email = email();
		MockHttpSession me = reader(email);
		long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
		send(post("/api/v1/me/account/delete"), me, "{\"password\":\"" + PASSWORD + "\"}");
		clock.set(START.plus(Duration.ofDays(8)));
		privacy.purgeDue();

		// Simulate restoring an older backup: the account row and the ledger row come back as they were.
		jdbc.update("DELETE FROM account_deletion_ledger WHERE user_id = ?", userId);
		jdbc.update("""
				INSERT INTO app_user (id, account_type, email, password_hash, status, age_declared_at,
				                      email_verified_at, security_stamp, password_changed_at)
				OVERRIDING SYSTEM VALUE
				VALUES (?, 'READER', ?, 'x', 'ACTIVE', now(), now(), 'restored', now())
				""", userId, "restored-" + email);

		assertThat(privacy.reapplyLedger()).isGreaterThanOrEqualTo(1);
		assertThat(count("SELECT count(*) FROM app_user WHERE id = ?", userId)).isZero();
	}

	@Test
	void staffCannotUseReaderSelfDeletion() throws Exception {
		mvc.perform(post("/api/v1/me/account/delete").with(csrf())).andExpect(status().isUnauthorized());
	}

	// --- helpers ---

	private int count(String sql, long id) {
		Integer n = jdbc.queryForObject(sql, Integer.class, id);
		return (n == null) ? 0 : n;
	}

	private long article(String title) {
		String code = "pv-" + UUID.randomUUID().toString().substring(0, 8);
		NewsSource src = rights.proposeSource(code, "Publisher " + code, "https://pub.example", "en");
		rights.recordRightsDecision(src.getId(), ContentUse.METADATA, new RightsReview(RightsStatus.PERMITTED, null,
				null, null, "reviewer", "Licence allows it", null, LocalDate.of(2027, 1, 1)));
		rights.activateSource(src.getId(), "manager", "Approved");
		String link = "https://pub.example/" + UUID.randomUUID();
		upserts.upsert(src.getId(), new FeedEntry(null, link, title, null, null, null, START, null, List.of()), link,
				"en", false, clock.instant());
		return articles.findBySourceIdAndCanonicalUrl(src.getId(), link).orElseThrow().getId();
	}

	private MockHttpSession reader(String email) throws Exception {
		send(post("/api/v1/auth/register"), null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"ageConfirmed\":true}")
			.andExpect(status().isAccepted());
		String token = CapturingMailGateway.token(mail.await(email, "Confirm your email"));
		send(post("/api/v1/auth/verify-email"), null, "{\"token\":\"" + token + "\"}")
			.andExpect(status().isNoContent());
		return login(email);
	}

	private MockHttpSession login(String email) throws Exception {
		return (MockHttpSession) loginRequest(email, PASSWORD).andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	private ResultActions loginRequest(String email, String password) throws Exception {
		return send(post("/api/v1/auth/login"), new MockHttpSession(),
				"{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	private ResultActions send(MockHttpServletRequestBuilder request, MockHttpSession session, String json)
			throws Exception {
		if (session != null) {
			request.session(session);
		}
		return mvc.perform(request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String email() {
		return "p" + UUID.randomUUID().toString().substring(0, 12) + "@example.org";
	}

}
