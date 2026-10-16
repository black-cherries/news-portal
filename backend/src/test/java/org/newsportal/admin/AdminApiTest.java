package org.newsportal.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.newsportal.TestClockConfig.MutableClock;
import org.newsportal.TestMailConfig.CapturingMailGateway;
import org.newsportal.identity.AccountService;
import org.newsportal.identity.RateLimiter;
import org.newsportal.ingestion.StubFeedServer;
import org.newsportal.ingestion.StubFeedServer.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminApiTest {

	private static final Instant START = Instant.parse("2026-07-01T09:00:00Z");

	private static final String PASSWORD = "staff member passphrase";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private AccountService accounts;

	@Autowired
	private CapturingMailGateway mail;

	@Autowired
	private MutableClock clock;

	@Autowired
	private RateLimiter limiter;

	@Autowired
	private JdbcTemplate jdbc;

	private StubFeedServer server;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(START);
		limiter.reset();
		server = new StubFeedServer();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	// --- access control ---

	@Test
	void onlyStaffCanReadAndOnlySourceManagersCanChange() throws Exception {
		MockHttpSession analyst = staff("ANALYST");
		MockHttpSession admin = staff("SYSTEM_ADMIN");
		MockHttpSession reader = reader();

		mvc.perform(get("/api/v1/admin/sources")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/admin/sources").session(reader)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/sources").session(analyst)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/admin/source-health").session(analyst)).andExpect(status().isOk());

		send(post("/api/v1/admin/sources"), analyst, newSourceJson(code())).andExpect(status().isForbidden());
		// Separation of duties: system admins manage people, not publisher rights.
		send(post("/api/v1/admin/sources"), admin, newSourceJson(code())).andExpect(status().isForbidden());
	}

	@Test
	void onlySystemAdminsManageStaffAndSeeTheAuditLog() throws Exception {
		MockHttpSession manager = staff("SOURCE_MANAGER");
		mvc.perform(get("/api/v1/admin/staff").session(manager)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/audit").session(manager)).andExpect(status().isForbidden());

		MockHttpSession admin = staff("SYSTEM_ADMIN");
		mvc.perform(get("/api/v1/admin/staff").session(admin)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/admin/audit").session(admin)).andExpect(status().isOk())
			.andExpect(jsonPath("$[*].action", hasItem("STAFF_SIGN_IN")));
	}

	// --- publisher lifecycle ---

	@Test
	void sourceManagerTakesAPublisherFromProposalToHealthyIngestion() throws Exception {
		String managerEmail = email();
		MockHttpSession manager = staffWithEmail(managerEmail, "SOURCE_MANAGER");
		String code = code();

		long id = ((Number) JsonPath.read(send(post("/api/v1/admin/sources"), manager, newSourceJson(code))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PROPOSED"))
			.andExpect(jsonPath("$.rights", hasSize(8)))
			.andExpect(jsonPath("$.rights[0].reason").value("SOURCE_NOT_ACTIVE"))
			.andReturn().getResponse().getContentAsString(), "$.id")).longValue();

		// Cannot activate before metadata is permitted.
		send(post("/api/v1/admin/sources/" + id + "/activate"), manager, "{\"notes\":\"go\"}")
			.andExpect(status().isConflict());

		send(put("/api/v1/admin/sources/" + id + "/rights/metadata"), manager, """
				{"status":"PERMITTED","notes":"Licence s.2 allows headlines and links",
				 "evidenceUrl":"https://pub.example/terms","nextReviewDue":"2027-01-01"}
				""").andExpect(status().isOk());
		send(post("/api/v1/admin/sources/" + id + "/activate"), manager, "{\"notes\":\"Passed review\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.rights[0].use").value("METADATA"))
			.andExpect(jsonPath("$.rights[0].allowed").value(true))
			.andExpect(jsonPath("$.rights[1].allowed").value(false))
			.andExpect(jsonPath("$.rights[1].reason").value("NO_RIGHTS_RECORD"));

		server.respond("/rss", Response.ok("""
				<rss version="2.0"><channel><title>P</title><link>https://pub.example/</link><description>d</description>
				<item><title>Story</title><link>https://pub.example/s1</link><guid>s1</guid></item>
				</channel></rss>
				"""));
		long feedId = ((Number) JsonPath.read(send(post("/api/v1/admin/sources/" + id + "/feeds"), manager,
				"{\"feedUrl\":\"" + server.url("/rss") + "\",\"refreshIntervalMinutes\":15}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.health").value("PENDING"))
			.andReturn().getResponse().getContentAsString(), "$.id")).longValue();

		send(post("/api/v1/admin/feeds/" + feedId + "/run"), manager, "")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("SUCCESS"))
			.andExpect(jsonPath("$.itemsCreated").value(1));

		mvc.perform(get("/api/v1/admin/feeds/" + feedId + "/jobs").session(manager))
			.andExpect(jsonPath("$", hasSize(1)));
		mvc.perform(get("/api/v1/admin/source-health").session(manager))
			.andExpect(jsonPath("$[?(@.feedId == " + feedId + ")].health").value("HEALTHY"));

		mvc.perform(get("/api/v1/admin/sources/" + id).session(manager))
			.andExpect(jsonPath("$.reviews[0].action").value("SOURCE_ACTIVATED"))
			.andExpect(jsonPath("$.reviews[0].reviewer").value(managerEmail))
			.andExpect(jsonPath("$.reviews[1].action").value("RIGHTS_DECISION"))
			.andExpect(jsonPath("$.reviews[1].evidenceUrl").value("https://pub.example/terms"));

		Long managerId = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, managerEmail);
		assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE actor_user_id = ? ORDER BY id", String.class,
				managerId))
			.containsSubsequence("SOURCE_CREATED", "RIGHTS_DECISION", "SOURCE_ACTIVATED", "FEED_ADDED",
					"FEED_RUN_MANUALLY");
	}

	@Test
	void useSuspensionAndRestoreThroughTheApi() throws Exception {
		MockHttpSession manager = staff("SOURCE_MANAGER");
		long id = activeSource(manager);
		String base = "/api/v1/admin/sources/" + id + "/rights/";

		send(post(base + "metadata/suspend"), manager, "{\"notes\":\"Complaint received\"}")
			.andExpect(jsonPath("$.rights[0].suspended").value(true))
			.andExpect(jsonPath("$.rights[0].allowed").value(false))
			.andExpect(jsonPath("$.rights[0].reason").value("USE_SUSPENDED"));
		send(post(base + "metadata/suspend"), manager, "{\"notes\":\"again\"}").andExpect(status().isConflict());
		send(post(base + "metadata/restore"), manager, "{\"notes\":\"Resolved with publisher\"}")
			.andExpect(jsonPath("$.rights[0].allowed").value(true));
	}

	@Test
	void disablingAFeedShowsAsDisabledNotFailing() throws Exception {
		MockHttpSession manager = staff("SOURCE_MANAGER");
		long id = activeSource(manager);
		long feedId = ((Number) JsonPath.read(send(post("/api/v1/admin/sources/" + id + "/feeds"), manager,
				"{\"feedUrl\":\"https://pub.example/rss\"}").andReturn().getResponse().getContentAsString(), "$.id"))
			.longValue();

		send(patch("/api/v1/admin/feeds/" + feedId), manager, "{\"refreshIntervalMinutes\":1}")
			.andExpect(status().isBadRequest());
		send(patch("/api/v1/admin/feeds/" + feedId), manager, "{\"enabled\":false}")
			.andExpect(jsonPath("$.health").value("DISABLED"));
	}

	@Test
	void invalidInputIsRejectedClearly() throws Exception {
		MockHttpSession manager = staff("SOURCE_MANAGER");
		String code = code();
		send(post("/api/v1/admin/sources"), manager, newSourceJson("Bad Code!")).andExpect(status().isBadRequest());
		send(post("/api/v1/admin/sources"), manager, """
				{"code":"%s","name":"X","homepageUrl":"javascript:alert(1)","primaryLanguage":"en"}
				""".formatted(code)).andExpect(status().isBadRequest());
		send(post("/api/v1/admin/sources"), manager, """
				{"code":"%s","name":"X","homepageUrl":"https://x.example","primaryLanguage":"fr"}
				""".formatted(code)).andExpect(status().isBadRequest());
		send(post("/api/v1/admin/sources"), manager, newSourceJson(code)).andExpect(status().isCreated());
		send(post("/api/v1/admin/sources"), manager, newSourceJson(code)).andExpect(status().isConflict());

		long id = activeSource(manager);
		send(put("/api/v1/admin/sources/" + id + "/rights/telepathy"), manager,
				"{\"status\":\"PERMITTED\",\"notes\":\"x\"}").andExpect(status().isBadRequest());
		send(put("/api/v1/admin/sources/" + id + "/rights/narration"), manager, "{\"notes\":\"no status\"}")
			.andExpect(status().isBadRequest());
		send(put("/api/v1/admin/sources/" + id + "/rights/narration"), manager,
				"{\"status\":\"MAYBE\",\"notes\":\"x\"}").andExpect(status().isBadRequest());
		send(put("/api/v1/admin/sources/" + id + "/rights/narration"), manager, "{\"status\":\"PERMITTED\"}")
			.andExpect(status().isBadRequest());
		send(post("/api/v1/admin/sources/999999999/suspend"), manager, "{\"notes\":\"x\"}")
			.andExpect(status().isNotFound());
	}

	// --- staff management ---

	@Test
	void invitedStaffSetTheirOwnPasswordAndGetTheirRoles() throws Exception {
		MockHttpSession admin = staff("SYSTEM_ADMIN");
		String email = email();

		long id = ((Number) JsonPath.read(send(post("/api/v1/admin/staff"), admin,
				"{\"email\":\"" + email + "\",\"roles\":[\"CONTENT_REVIEWER\"]}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
			.andReturn().getResponse().getContentAsString(), "$.id")).longValue();

		String token = CapturingMailGateway.token(mail.await(email, "invited"));
		// Until the invite is accepted the account has an unusable random password.
		login(email, "my own staff passphrase").andExpect(status().isUnauthorized());
		send(post("/api/v1/auth/password-reset/confirm"), null,
				"{\"token\":\"" + token + "\",\"newPassword\":\"my own staff passphrase\"}")
			.andExpect(status().isNoContent());

		MockHttpSession invitee = session(login(email, "my own staff passphrase").andExpect(status().isOk()));
		mvc.perform(get("/api/v1/auth/me").session(invitee)).andExpect(jsonPath("$.roles[0]").value("CONTENT_REVIEWER"));

		// Role changes apply to the live session.
		send(put("/api/v1/admin/staff/" + id + "/roles"), admin, "{\"roles\":[\"ANALYST\",\"SOURCE_MANAGER\"]}")
			.andExpect(jsonPath("$.roles", hasSize(2)));
		mvc.perform(get("/api/v1/auth/me").session(invitee)).andExpect(jsonPath("$.roles[1]").value("SOURCE_MANAGER"));

		send(post("/api/v1/admin/staff/" + id + "/disable"), admin, "").andExpect(jsonPath("$.status").value("DISABLED"));
		mvc.perform(get("/api/v1/auth/me").session(invitee)).andExpect(status().isUnauthorized());
	}

	@Test
	void adminsCannotLockThemselvesOut() throws Exception {
		String email = email();
		MockHttpSession admin = staffWithEmail(email, "SYSTEM_ADMIN");
		long self = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);

		send(put("/api/v1/admin/staff/" + self + "/roles"), admin, "{\"roles\":[\"ANALYST\"]}")
			.andExpect(status().isConflict());
		send(post("/api/v1/admin/staff/" + self + "/disable"), admin, "").andExpect(status().isConflict());
		send(post("/api/v1/admin/staff"), admin, "{\"email\":\"" + email() + "\",\"roles\":[\"OWNER\"]}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void readerAccountsAreNotStaff() throws Exception {
		MockHttpSession admin = staff("SYSTEM_ADMIN");
		String readerEmail = email();
		reader(readerEmail);
		long readerId = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, readerEmail);

		send(put("/api/v1/admin/staff/" + readerId + "/roles"), admin, "{\"roles\":[\"ANALYST\"]}")
			.andExpect(status().isNotFound());
	}

	// --- helpers ---

	private long activeSource(MockHttpSession manager) throws Exception {
		long id = ((Number) JsonPath.read(send(post("/api/v1/admin/sources"), manager, newSourceJson(code()))
			.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
		send(put("/api/v1/admin/sources/" + id + "/rights/METADATA"), manager,
				"{\"status\":\"PERMITTED\",\"notes\":\"ok\"}").andExpect(status().isOk());
		send(post("/api/v1/admin/sources/" + id + "/activate"), manager, "{\"notes\":\"ok\"}")
			.andExpect(status().isOk());
		return id;
	}

	private MockHttpSession staff(String... roles) throws Exception {
		return staffWithEmail(email(), roles);
	}

	private MockHttpSession staffWithEmail(String email, String... roles) throws Exception {
		accounts.createStaffAccount(email, PASSWORD, List.of(roles), null);
		return session(login(email, PASSWORD).andExpect(status().isOk()));
	}

	private MockHttpSession reader() throws Exception {
		return reader(email());
	}

	private MockHttpSession reader(String email) throws Exception {
		send(post("/api/v1/auth/register"), null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"ageConfirmed\":true}");
		String token = CapturingMailGateway.token(mail.await(email, "Confirm your email"));
		send(post("/api/v1/auth/verify-email"), null, "{\"token\":\"" + token + "\"}");
		return session(login(email, PASSWORD).andExpect(status().isOk()));
	}

	private ResultActions login(String email, String password) throws Exception {
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

	private static MockHttpSession session(ResultActions login) {
		MvcResult result = login.andReturn();
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	private static String newSourceJson(String code) {
		return """
				{"code":"%s","name":"Example News","homepageUrl":"https://pub.example","primaryLanguage":"en",
				 "termsUrl":"https://pub.example/terms","termsSummary":"Headlines and links allowed"}
				""".formatted(code);
	}

	private static String code() {
		return "pub-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static String email() {
		return "s" + UUID.randomUUID().toString().substring(0, 12) + "@example.org";
	}

}
