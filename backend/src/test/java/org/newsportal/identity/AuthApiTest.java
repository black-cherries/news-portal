package org.newsportal.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.newsportal.TestClockConfig.MutableClock;
import org.newsportal.TestMailConfig.CapturingMailGateway;
import org.newsportal.mail.MailGateway.OutgoingMail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

	private static final Instant START = Instant.parse("2026-05-01T08:00:00Z");

	private static final String PASSWORD = "correct horse battery";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private CapturingMailGateway mail;

	@Autowired
	private MutableClock clock;

	@Autowired
	private RateLimiter limiter;

	@Autowired
	private AccountService accounts;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		clock.set(START);
		limiter.reset();
	}

	// --- registration and verification ---

	@Test
	void fullSignUpVerifySignInSignOutFlow() throws Exception {
		String email = email();
		register(email, PASSWORD).andExpect(status().isAccepted());
		String token = CapturingMailGateway.token(mail.await(email, "Confirm your email"));

		login(email, PASSWORD).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

		postJson("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}").andExpect(status().isNoContent());

		MockHttpSession session = signIn(email, PASSWORD);
		mvc.perform(get("/api/v1/auth/me").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.accountType").value("READER"))
			.andExpect(jsonPath("$.roles").isEmpty());

		mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void emailIsCaseInsensitive() throws Exception {
		String email = email();
		activeReader(email);
		login(email.toUpperCase(), PASSWORD).andExpect(status().isOk());
	}

	@Test
	void verificationTokensAreSingleUseAndExpire() throws Exception {
		String email = email();
		register(email, PASSWORD);
		String first = CapturingMailGateway.token(mail.await(email, "Confirm your email"));

		// Resending retires the first token.
		postJson("/api/v1/auth/resend-verification", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
		String second = CapturingMailGateway.token(mail.await(email, "Confirm your email"));
		verify(first).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_LINK"));

		clock.set(START.plus(AccountService.VERIFY_TTL));
		verify(second).andExpect(status().isBadRequest());

		postJson("/api/v1/auth/resend-verification", "{\"email\":\"" + email + "\"}");
		String third = CapturingMailGateway.token(mail.await(email, "Confirm your email"));
		verify(third).andExpect(status().isNoContent());
		verify(third).andExpect(status().isBadRequest());
	}

	@Test
	void registeringAnExistingAddressLooksIdenticalAndNotifiesTheOwner() throws Exception {
		String taken = email();
		activeReader(taken);
		String fresh = email();

		String forTaken = register(taken, "another long password").andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		String forFresh = register(fresh, "another long password").andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();

		assertThat(forTaken).isEqualTo(forFresh);
		mail.await(taken, "tried to sign up");
		mail.await(fresh, "Confirm your email");
	}

	@Test
	void registrationValidatesInput() throws Exception {
		postJson("/api/v1/auth/register",
				"{\"email\":\"" + email() + "\",\"password\":\"" + PASSWORD + "\",\"ageConfirmed\":false}")
			.andExpect(status().isBadRequest());
		register(email(), "short").andExpect(status().isBadRequest());
		register("not-an-email", PASSWORD).andExpect(status().isBadRequest());
		postJson("/api/v1/auth/register", "{not json").andExpect(status().isBadRequest());
	}

	@Test
	void unsafeRequestsWithoutCsrfTokenAreRejected() throws Exception {
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"a@b.example\",\"password\":\"x\"}"))
			.andExpect(status().isForbidden());
	}

	// --- sign-in ---

	@Test
	void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
		String email = email();
		activeReader(email);

		String wrong = login(email, "not the password").andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		String unknown = login(email(), "not the password").andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();

		assertThat(wrong).isEqualTo(unknown).contains("INVALID_CREDENTIALS");
	}

	@Test
	void repeatedFailuresLockTheAccountTemporarily() throws Exception {
		String email = email();
		activeReader(email);
		for (int i = 0; i < AccountService.MAX_FAILED_LOGINS; i++) {
			login(email, "wrong password " + i).andExpect(status().isUnauthorized());
		}

		login(email, PASSWORD).andExpect(status().isUnauthorized());

		clock.set(START.plus(AccountService.LOCK_FOR));
		login(email, PASSWORD).andExpect(status().isOk());
	}

	@Test
	void signInRotatesTheSessionId() throws Exception {
		String email = email();
		activeReader(email);
		MockHttpSession before = new MockHttpSession();
		String oldId = before.getId();

		MvcResult result = login(email, PASSWORD, before).andExpect(status().isOk()).andReturn();

		assertThat(result.getRequest().getSession().getId()).isNotEqualTo(oldId);
	}

	@Test
	void signInAttemptsAreRateLimitedPerAddress() throws Exception {
		for (int i = 0; i < 50; i++) {
			login(email(), "whatever password");
		}
		login(email(), "whatever password").andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"));
	}

	@Test
	void registrationIsRateLimitedPerAddress() throws Exception {
		for (int i = 0; i < 10; i++) {
			register(email(), PASSWORD).andExpect(status().isAccepted());
		}
		register(email(), PASSWORD).andExpect(status().isTooManyRequests());
	}

	// --- password recovery and change ---

	@Test
	void passwordResetFlowEndsOldSessions() throws Exception {
		String email = email();
		activeReader(email);
		MockHttpSession oldSession = signIn(email, PASSWORD);

		String unknownReply = postJson("/api/v1/auth/password-reset/request", "{\"email\":\"" + email() + "\"}")
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		String knownReply = postJson("/api/v1/auth/password-reset/request", "{\"email\":\"" + email + "\"}")
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		assertThat(knownReply).isEqualTo(unknownReply);
		String token = CapturingMailGateway.token(mail.await(email, "Reset your password"));

		// A rejected password does not use up the token.
		reset(token, "short").andExpect(status().isBadRequest());
		reset(token, "a brand new passphrase").andExpect(status().isNoContent());
		reset(token, "another new passphrase").andExpect(status().isBadRequest());
		mail.await(email, "password was changed");

		mvc.perform(get("/api/v1/auth/me").session(oldSession)).andExpect(status().isUnauthorized());
		login(email, PASSWORD).andExpect(status().isUnauthorized());
		login(email, "a brand new passphrase").andExpect(status().isOk());
	}

	@Test
	void resetTokenExpiresAfterOneHour() throws Exception {
		String email = email();
		activeReader(email);
		postJson("/api/v1/auth/password-reset/request", "{\"email\":\"" + email + "\"}");
		String token = CapturingMailGateway.token(mail.await(email, "Reset your password"));

		clock.set(START.plus(Duration.ofHours(1)));
		reset(token, "a brand new passphrase").andExpect(status().isBadRequest());
	}

	@Test
	void changingPasswordKeepsThisSessionAndEndsOthers() throws Exception {
		String email = email();
		activeReader(email);
		MockHttpSession phone = signIn(email, PASSWORD);
		MockHttpSession laptop = signIn(email, PASSWORD);

		mvc.perform(post("/api/v1/auth/password/change").session(laptop).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\":\"wrong one here\",\"newPassword\":\"a brand new passphrase\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/auth/password/change").session(laptop).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"a brand new passphrase\"}"))
			.andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/auth/me").session(laptop)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/auth/me").session(phone)).andExpect(status().isUnauthorized());
	}

	@Test
	void disablingAnAccountEndsItsSessionImmediately() throws Exception {
		String email = email();
		activeReader(email);
		MockHttpSession session = signIn(email, PASSWORD);

		jdbc.update("UPDATE app_user SET status = 'DISABLED', security_stamp = 'revoked' WHERE email = ?", email);

		mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
		login(email, PASSWORD).andExpect(status().isUnauthorized());
	}

	// --- staff separation ---

	@Test
	void readersCannotReachStaffEndpoints() throws Exception {
		String email = email();
		activeReader(email);
		MockHttpSession reader = signIn(email, PASSWORD);

		mvc.perform(get("/api/v1/admin/anything")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/admin/anything").session(reader)).andExpect(status().isForbidden());
	}

	@Test
	void staffSignInCarriesRolesAndIsAudited() throws Exception {
		String email = email();
		AppUser staff = accounts.createStaffAccount(email, PASSWORD, List.of("SOURCE_MANAGER"), null);
		MockHttpSession session = signIn(email, PASSWORD);

		mvc.perform(get("/api/v1/auth/me").session(session))
			.andExpect(jsonPath("$.accountType").value("STAFF"))
			.andExpect(jsonPath("$.roles[0]").value("SOURCE_MANAGER"));
		// Passes security (no such endpoint yet), unlike the reader above.
		mvc.perform(get("/api/v1/admin/anything").session(session)).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'STAFF_SIGN_IN' AND actor_user_id = ?",
				Integer.class, staff.getId())).isEqualTo(1);
	}

	@Test
	void databaseRefusesStaffRolesForReaderAccounts() throws Exception {
		String email = email();
		activeReader(email);
		Long readerId = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO user_role (user_id, role_id) SELECT ?, id FROM role WHERE code = 'SYSTEM_ADMIN'", readerId))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void passwordsAreStoredAsArgon2Hashes() throws Exception {
		String email = email();
		activeReader(email);
		String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = ?", String.class, email);
		assertThat(hash).startsWith("{argon2}$argon2id$").doesNotContain(PASSWORD);
		String tokenHashes = String.join(",", jdbc.queryForList(
				"SELECT token_hash FROM auth_token t JOIN app_user u ON u.id = t.user_id WHERE u.email = ?",
				String.class, email));
		assertThat(tokenHashes).matches("[0-9a-f]{64}(,[0-9a-f]{64})*");
	}

	// --- helpers ---

	private void activeReader(String email) throws Exception {
		register(email, PASSWORD).andExpect(status().isAccepted());
		OutgoingMail m = mail.await(email, "Confirm your email");
		verify(CapturingMailGateway.token(m)).andExpect(status().isNoContent());
	}

	private MockHttpSession signIn(String email, String password) throws Exception {
		MvcResult result = login(email, password).andExpect(status().isOk()).andReturn();
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	private ResultActions register(String email, String password) throws Exception {
		return postJson("/api/v1/auth/register",
				"{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"ageConfirmed\":true}");
	}

	private ResultActions verify(String token) throws Exception {
		return postJson("/api/v1/auth/verify-email", "{\"token\":\"" + token + "\"}");
	}

	private ResultActions reset(String token, String password) throws Exception {
		return postJson("/api/v1/auth/password-reset/confirm",
				"{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}");
	}

	private ResultActions login(String email, String password) throws Exception {
		return login(email, password, new MockHttpSession());
	}

	private ResultActions login(String email, String password, MockHttpSession session) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	private ResultActions postJson(String url, String json) throws Exception {
		return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String email() {
		return "u" + UUID.randomUUID().toString().substring(0, 12) + "@example.org";
	}

}
