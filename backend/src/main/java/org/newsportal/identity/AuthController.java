package org.newsportal.identity;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.newsportal.identity.AccountService.LoginResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account endpoints. Responses for unknown emails match those for known ones (FR-042). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private static final Map<String, String> CHECK_EMAIL = Map.of("message",
			"If the address can be used, we have sent an email with next steps.");

	private final AccountService accounts;

	private final SecurityContextRepository contexts;

	private final CsrfTokenRepository csrfTokens;

	public AuthController(AccountService accounts, SecurityContextRepository contexts, CsrfTokenRepository csrfTokens) {
		this.accounts = accounts;
		this.contexts = contexts;
		this.csrfTokens = csrfTokens;
	}

	public record RegisterRequest(String email, String password, boolean ageConfirmed, String uiLanguage) {
	}

	public record EmailRequest(String email) {
	}

	public record TokenRequest(String token) {
	}

	public record LoginRequest(String email, String password) {
	}

	public record ResetRequest(String token, String newPassword) {
	}

	public record ChangePasswordRequest(String currentPassword, String newPassword) {
	}

	public record Me(long id, String email, String accountType, List<String> roles) {

		static Me of(AuthPrincipal p) {
			return new Me(p.userId(), p.email(), p.accountType().name(), p.staffRoles().stream().sorted().toList());
		}

	}

	/** Makes sure the XSRF-TOKEN cookie is set; the SPA calls this before its first unsafe request. */
	@GetMapping("/csrf")
	public Map<String, String> csrf(CsrfToken token) {
		return Map.of("headerName", token.getHeaderName());
	}

	@PostMapping("/register")
	public ResponseEntity<Map<String, String>> register(@RequestBody RegisterRequest body, HttpServletRequest request) {
		accounts.register(body.email(), body.password(), body.ageConfirmed(), body.uiLanguage(), ip(request));
		return ResponseEntity.accepted().body(CHECK_EMAIL);
	}

	@PostMapping("/verify-email")
	public ResponseEntity<?> verifyEmail(@RequestBody TokenRequest body, HttpServletRequest request) {
		return accounts.verifyEmail(body.token(), ip(request)) ? ResponseEntity.noContent().build()
				: invalidLink();
	}

	@PostMapping("/resend-verification")
	public ResponseEntity<Map<String, String>> resend(@RequestBody EmailRequest body, HttpServletRequest request) {
		accounts.resendVerification(body.email(), ip(request));
		return ResponseEntity.accepted().body(CHECK_EMAIL);
	}

	@PostMapping("/login")
	public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		LoginResult result = accounts.authenticate(body.email(), body.password(), ip(request));
		return switch (result.outcome()) {
			case INVALID_CREDENTIALS -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(Map.of("code", "INVALID_CREDENTIALS", "message", "Email or password is incorrect."));
			case EMAIL_NOT_VERIFIED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("code", "EMAIL_NOT_VERIFIED", "message", "Confirm your email address first."));
			case DELETION_PENDING -> ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("code", "ACCOUNT_DELETION_PENDING", "message",
						"This account is scheduled for deletion. You can restore it until the date shown.",
						"deletionDueAt", result.deletionDueAt().toString()));
			case SUCCESS -> {
				startSession(result.principal(), request, response);
				yield ResponseEntity.ok(Me.of(result.principal()));
			}
		};
	}

	/** Cancels a pending deletion. Same 401 for every failure. Sign in again afterwards. */
	@PostMapping("/account/restore")
	public ResponseEntity<?> restoreAccount(@RequestBody LoginRequest body, HttpServletRequest request) {
		return accounts.restoreAccount(body.email(), body.password(), ip(request))
				? ResponseEntity.noContent().build()
				: ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(Map.of("code", "INVALID_CREDENTIALS", "message", "Email or password is incorrect."));
	}

	@GetMapping("/me")
	public Me me(@AuthenticationPrincipal AuthPrincipal principal) {
		return Me.of(principal);
	}

	@PostMapping("/password-reset/request")
	public ResponseEntity<Map<String, String>> requestReset(@RequestBody EmailRequest body,
			HttpServletRequest request) {
		accounts.requestPasswordReset(body.email(), ip(request));
		return ResponseEntity.accepted().body(CHECK_EMAIL);
	}

	@PostMapping("/password-reset/confirm")
	public ResponseEntity<?> confirmReset(@RequestBody ResetRequest body, HttpServletRequest request) {
		return accounts.resetPassword(body.token(), body.newPassword(), ip(request))
				? ResponseEntity.noContent().build() : invalidLink();
	}

	@PostMapping("/password/change")
	public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthPrincipal principal,
			@RequestBody ChangePasswordRequest body, HttpServletRequest request, HttpServletResponse response) {
		AuthPrincipal updated = accounts.changePassword(principal, body.currentPassword(), body.newPassword());
		startSession(updated, request, response);
		return ResponseEntity.noContent().build();
	}

	/** New session id (prevents fixation) and a fresh CSRF token. */
	private void startSession(AuthPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(SecurityConfig.authenticationFor(principal));
		SecurityContextHolder.setContext(context);
		contexts.saveContext(context, request, response);
		csrfTokens.saveToken(null, request, response);
	}

	private static ResponseEntity<Map<String, String>> invalidLink() {
		return ResponseEntity.badRequest()
			.body(Map.of("code", "INVALID_OR_EXPIRED_LINK", "message", "This link is invalid or has expired."));
	}

	/** Direct peer address. Configure forwarded-header handling before running behind a proxy. */
	private static String ip(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

}
