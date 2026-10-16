package org.newsportal.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.newsportal.mail.MailGateway.OutgoingMail;
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
class NotificationApiTest {

	private static final Instant START = Instant.parse("2026-11-01T08:00:00Z");

	private static final String PASSWORD = "notification test passphrase";

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
	private NotificationSender sender;

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
		// Each test starts with nobody subscribed and no pending alerts, so tests cannot affect each other.
		jdbc.update("UPDATE notification_setting SET digest_frequency = 'OFF', next_digest_at = NULL, breaking_alerts = FALSE");
		jdbc.update("""
				UPDATE breaking_alert SET status = 'CANCELLED', cancelled_at = now(), cancel_reason = 'test isolation'
				WHERE status IN ('QUEUED', 'SENDING')
				""");
	}

	@Test
	void settingsAreOffByDefaultValidatedAndExported() throws Exception {
		MockHttpSession me = reader(email());
		mvc.perform(get("/api/v1/me/notifications").session(me))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.digest").value("OFF"))
			.andExpect(jsonPath("$.breakingAlerts").value(false))
			.andExpect(jsonPath("$.languages", empty()))
			.andExpect(jsonPath("$.topics", empty()))
			.andExpect(jsonPath("$.nextDigestAt").value(nullValue()));

		send(put("/api/v1/me/notifications"), me,
				"{\"digest\":\"DAILY\",\"breakingAlerts\":true,\"languages\":[\"hi\",\"en\"],\"topics\":[\"sports\"]}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.digest").value("DAILY"))
			.andExpect(jsonPath("$.languages", contains("en", "hi")))
			.andExpect(jsonPath("$.topics", contains("sports")))
			.andExpect(jsonPath("$.nextDigestAt").value(START.plus(Duration.ofDays(1)).toString()));

		send(put("/api/v1/me/notifications"), me, "{\"digest\":\"HOURLY\"}").andExpect(status().isBadRequest());
		send(put("/api/v1/me/notifications"), me, "{\"breakingAlerts\":true}").andExpect(status().isBadRequest());
		send(put("/api/v1/me/notifications"), me, "{\"digest\":\"OFF\",\"languages\":[\"fr\"]}")
			.andExpect(status().isBadRequest());
		send(put("/api/v1/me/notifications"), me, "{\"digest\":\"OFF\",\"topics\":[\"no-such-topic\"]}")
			.andExpect(status().isBadRequest());

		send(post("/api/v1/me/export"), me, "{\"password\":\"" + PASSWORD + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.notifications.digest").value("DAILY"))
			.andExpect(jsonPath("$.notifications.breakingAlerts").value(true));

		mvc.perform(get("/api/v1/me/notifications")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me/notifications").session(staff("CONTENT_REVIEWER"))).andExpect(status().isForbidden());
	}

	@Test
	void dailyDigestUsesTheDigestLanguageLabelsOtherLanguagesAndListsSyndicatedCopiesOnce() throws Exception {
		String email = email();
		MockHttpSession me = reader(email);
		send(put("/api/v1/me/preferences"), me,
				"{\"uiLanguage\":\"en\",\"newsLanguages\":[],\"topics\":[],\"digestLanguage\":\"hi\"}")
			.andExpect(status().isOk());
		send(put("/api/v1/me/notifications"), me, "{\"digest\":\"DAILY\",\"languages\":[\"en\",\"hi\"]}")
			.andExpect(status().isOk());

		String tag = UUID.randomUUID().toString().substring(0, 8);
		clock.set(START.minus(Duration.ofDays(2)));
		article("Old news " + tag, "en");
		clock.set(START.plus(Duration.ofHours(2)));
		Created english = article("Flood warning for the coast " + tag, "en");
		Created copy = article("Flood warning for the coast " + tag + "!", "en");
		Created hindi = article("बाढ़ की चेतावनी " + tag, "hi");
		article("تحذير من الفيضانات " + tag, "ar");

		clock.set(START.plus(Duration.ofDays(1)).plusSeconds(60));
		sender.sendDueDigests();
		OutgoingMail digest = mail.await(email, "दैनिक");
		String body = digest.body();
		assertThat(body).contains("पिछले एक दिन के नए लेख");
		assertThat(body.split("Flood warning for the coast " + tag, -1)).hasSize(2);
		assertThat(body).contains("यही शीर्षक इनसे भी: ");
		assertThat(body).containsAnyOf(english.sourceName(), copy.sourceName());
		assertThat(body).contains("अंग्रेज़ी में").doesNotContain("हिन्दी में");
		assertThat(body).contains("बाढ़ की चेतावनी " + tag, "http://app.test/articles/" + hindi.id());
		assertThat(body).doesNotContain("Old news " + tag).doesNotContain("تحذير");
		assertThat(body).contains("http://app.test/settings");

		// Not sent twice, and the next one is due a day later.
		sender.sendDueDigests();
		assertThat(mail.drain(email)).isEmpty();
		mvc.perform(get("/api/v1/me/notifications").session(me))
			.andExpect(jsonPath("$.nextDigestAt").value(START.plus(Duration.ofDays(2)).toString()));

		// Nothing new: no empty digest.
		clock.set(START.plus(Duration.ofDays(2)).plusSeconds(60));
		sender.sendDueDigests();
		assertThat(mail.drain(email)).isEmpty();

		// The unsubscribe link works without signing in and only stops digests.
		send(put("/api/v1/me/notifications"), me, "{\"digest\":\"DAILY\",\"breakingAlerts\":true}");
		String token = unsubscribeToken(digest, "DIGEST");
		send(post("/api/v1/notifications/unsubscribe"), null, "{\"token\":\"" + token + "\",\"kind\":\"DIGEST\"}")
			.andExpect(status().isOk());
		mvc.perform(get("/api/v1/me/notifications").session(me))
			.andExpect(jsonPath("$.digest").value("OFF"))
			.andExpect(jsonPath("$.breakingAlerts").value(true));
		send(post("/api/v1/notifications/unsubscribe"), null, "{\"token\":\"" + token + "\",\"kind\":\"ALL\"}")
			.andExpect(status().isOk());
		mvc.perform(get("/api/v1/me/notifications").session(me)).andExpect(jsonPath("$.breakingAlerts").value(false));
	}

	@Test
	void unsubscribeRejectsUnknownLinks() throws Exception {
		send(post("/api/v1/notifications/unsubscribe"), null,
				"{\"token\":\"" + "A".repeat(43) + "\",\"kind\":\"ALL\"}")
			.andExpect(status().isNotFound());
		send(post("/api/v1/notifications/unsubscribe"), null, "{\"token\":\"short\"}").andExpect(status().isNotFound());
	}

	@Test
	void reviewersSendAlertsToMatchingReadersAfterTheHold() throws Exception {
		clock.set(START.plus(Duration.ofDays(10)));
		String subscribed = email();
		String hindiOnly = email();
		String notSubscribed = email();
		String sportsOnly = email();
		send(put("/api/v1/me/notifications"), reader(subscribed), "{\"digest\":\"OFF\",\"breakingAlerts\":true}");
		send(put("/api/v1/me/notifications"), reader(hindiOnly),
				"{\"digest\":\"OFF\",\"breakingAlerts\":true,\"languages\":[\"hi\"]}");
		reader(notSubscribed);
		send(put("/api/v1/me/notifications"), reader(sportsOnly),
				"{\"digest\":\"OFF\",\"breakingAlerts\":true,\"topics\":[\"sports\"]}");

		String title = "Major earthquake reported " + UUID.randomUUID().toString().substring(0, 8);
		Created story = article(title, "en");
		Created syndicated = article(title, "en");
		MockHttpSession reviewer = staff("CONTENT_REVIEWER");

		send(post("/api/v1/admin/alerts"), staff("ANALYST"), alert(story.id(), "Big story"))
			.andExpect(status().isForbidden());
		send(post("/api/v1/admin/alerts"), staff("SOURCE_MANAGER"), alert(story.id(), "Big story"))
			.andExpect(status().isForbidden());
		send(post("/api/v1/admin/alerts"), reviewer, alert(story.id(), " ")).andExpect(status().isBadRequest());
		send(post("/api/v1/admin/alerts"), reviewer, alert(999_999_999L, "Big story")).andExpect(status().isNotFound());

		String created = send(post("/api/v1/admin/alerts"), reviewer, alert(story.id(), "Confirmed by two agencies"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andExpect(jsonPath("$.articleTitle").value(title))
			.andExpect(jsonPath("$.sendAfter").value(clock.instant().plus(Duration.ofMinutes(2)).toString()))
			.andReturn().getResponse().getContentAsString();
		int id = JsonPath.read(created, "$.id");

		// FR-067: no second alert for the same article or the same headline from another publisher.
		send(post("/api/v1/admin/alerts"), reviewer, alert(story.id(), "Again")).andExpect(status().isConflict());
		send(post("/api/v1/admin/alerts"), reviewer, alert(syndicated.id(), "Copy")).andExpect(status().isConflict());

		// Held for two minutes so it can be cancelled.
		sender.sendDueAlerts();
		assertThat(mail.drain(subscribed)).isEmpty();

		clock.set(clock.instant().plus(Duration.ofMinutes(3)));
		sender.sendDueAlerts();
		OutgoingMail sent = mail.await(subscribed, "Breaking news: " + title);
		assertThat(sent.body()).contains("http://app.test/articles/" + story.id(), "&kind=ALERTS")
			.doesNotContain("Confirmed by two agencies");
		assertThat(mail.drain(hindiOnly)).isEmpty();
		assertThat(mail.drain(notSubscribed)).isEmpty();
		assertThat(mail.drain(sportsOnly)).isEmpty();

		mvc.perform(get("/api/v1/admin/alerts/" + id).session(reviewer))
			.andExpect(jsonPath("$.status").value("SENT"))
			.andExpect(jsonPath("$.recipientCount").value(1));
		send(post("/api/v1/admin/alerts/" + id + "/cancel"), reviewer, "{\"reason\":\"Too late\"}")
			.andExpect(status().isConflict());
		mvc.perform(get("/api/v1/admin/alerts").session(reviewer)).andExpect(jsonPath("$.items[0].id").value(id));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_log WHERE action = 'ALERT_CREATED' AND target_id = ?", Integer.class,
				String.valueOf(id))).isEqualTo(1);

		// Sending again does nothing.
		sender.sendDueAlerts();
		assertThat(mail.drain(subscribed)).isEmpty();
	}

	@Test
	void queuedAlertsCanBeCancelledAndRightsAreCheckedAgainBeforeSending() throws Exception {
		clock.set(START.plus(Duration.ofDays(20)));
		String subscribed = email();
		send(put("/api/v1/me/notifications"), reader(subscribed), "{\"digest\":\"OFF\",\"breakingAlerts\":true}");
		MockHttpSession reviewer = staff("CONTENT_REVIEWER");
		Created mistaken = article("Wrong story " + UUID.randomUUID(), "en");
		Created withdrawn = article("Withdrawn story " + UUID.randomUUID(), "en");

		int first = JsonPath.read(send(post("/api/v1/admin/alerts"), reviewer, alert(mistaken.id(), "Breaking"))
			.andReturn().getResponse().getContentAsString(), "$.id");
		send(post("/api/v1/admin/alerts/" + first + "/cancel"), reviewer, "{\"reason\":\"Wrong story\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancelReason").value("Wrong story"));
		send(post("/api/v1/admin/alerts/" + first + "/cancel"), reviewer, "{\"reason\":\"Again\"}")
			.andExpect(status().isConflict());

		int second = JsonPath.read(send(post("/api/v1/admin/alerts"), reviewer, alert(withdrawn.id(), "Breaking"))
			.andReturn().getResponse().getContentAsString(), "$.id");
		jdbc.update("UPDATE news_source SET status = 'SUSPENDED' WHERE id = ?", withdrawn.sourceId());

		clock.set(clock.instant().plus(Duration.ofMinutes(3)));
		sender.sendDueAlerts();
		assertThat(mail.drain(subscribed)).isEmpty();
		mvc.perform(get("/api/v1/admin/alerts/" + second).session(reviewer))
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.recipientCount").value(0));

		// A cancelled alert does not block a new one for the same article.
		send(post("/api/v1/admin/alerts"), reviewer, alert(mistaken.id(), "Now confirmed"))
			.andExpect(status().isCreated());
	}

	@Test
	void alertsAreLimitedPerReaderAndOverall() throws Exception {
		clock.set(START.plus(Duration.ofDays(30)));
		String subscribed = email();
		send(put("/api/v1/me/notifications"), reader(subscribed), "{\"digest\":\"OFF\",\"breakingAlerts\":true}");
		MockHttpSession reviewer = staff("CONTENT_REVIEWER");

		int last = 0;
		for (int i = 0; i < 4; i++) {
			Created story = article("Developing story " + i + " " + UUID.randomUUID(), "en");
			last = JsonPath.read(send(post("/api/v1/admin/alerts"), reviewer, alert(story.id(), "Breaking"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		}
		clock.set(clock.instant().plus(Duration.ofMinutes(3)));
		sender.sendDueAlerts();
		assertThat(mail.drain(subscribed)).hasSize(3);
		mvc.perform(get("/api/v1/admin/alerts/" + last).session(reviewer))
			.andExpect(jsonPath("$.recipientCount").value(0))
			.andExpect(jsonPath("$.skippedCount").value(1));

		// At most 6 alerts in 24 hours across the app.
		for (int i = 0; i < 2; i++) {
			send(post("/api/v1/admin/alerts"), reviewer, alert(article("Extra " + UUID.randomUUID(), "en").id(), "Breaking"))
				.andExpect(status().isCreated());
		}
		send(post("/api/v1/admin/alerts"), reviewer, alert(article("One too many " + UUID.randomUUID(), "en").id(), "Breaking"))
			.andExpect(status().isConflict());
	}

	// --- helpers ---

	private record Created(long id, long sourceId, String sourceName) {
	}

	private Created article(String title, String language) {
		String code = "nt-" + UUID.randomUUID().toString().substring(0, 8);
		NewsSource src = rights.proposeSource(code, "Publisher " + code, "https://pub.example", language);
		rights.recordRightsDecision(src.getId(), ContentUse.METADATA, new RightsReview(RightsStatus.PERMITTED, null,
				null, null, "reviewer", "Licence allows it", null, LocalDate.of(2028, 1, 1)));
		rights.activateSource(src.getId(), "manager", "Approved");
		String link = "https://pub.example/" + UUID.randomUUID();
		upserts.upsert(src.getId(), new FeedEntry(null, link, title, null, null, null, clock.instant(), null, List.of()),
				link, language, false, clock.instant());
		long id = articles.findBySourceIdAndCanonicalUrl(src.getId(), link).orElseThrow().getId();
		return new Created(id, src.getId(), src.getName());
	}

	private static String alert(long articleId, String reason) {
		return "{\"articleId\":" + articleId + ",\"reason\":\"" + reason + "\"}";
	}

	private static String unsubscribeToken(OutgoingMail mail, String kind) {
		Matcher m = Pattern.compile("/unsubscribe#token=([A-Za-z0-9_-]{43})&kind=" + kind).matcher(mail.body());
		assertThat(m.find()).as("unsubscribe link").isTrue();
		return m.group(1);
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
			.andReturn().getRequest().getSession();
	}

	private ResultActions send(MockHttpServletRequestBuilder request, MockHttpSession session, String json)
			throws Exception {
		request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
		if (session != null) {
			request.session(session);
		}
		return mvc.perform(request);
	}

	private static String email() {
		return "nt-" + UUID.randomUUID() + "@example.org";
	}

}
