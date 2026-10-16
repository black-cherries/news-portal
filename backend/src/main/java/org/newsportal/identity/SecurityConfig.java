package org.newsportal.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * Session-cookie authentication for the browser app (FR-043, FR-074).
 *
 * - Sessions: HttpOnly, SameSite=Lax cookie; Secure in the pilot profile.
 * - CSRF: double-submit cookie (XSRF-TOKEN cookie, X-XSRF-TOKEN header) for every unsafe request.
 * - Authorization is enforced here and with @PreAuthorize, never only in the frontend.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	static final String STAFF = "STAFF";

	@Bean
	PasswordEncoder passwordEncoder() {
		// Argon2id. The {argon2} prefix in stored hashes lets the settings change later.
		String id = "argon2";
		DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder(id,
				Map.of(id, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
		return encoder;
	}

	@Bean
	CsrfTokenRepository csrfTokenRepository() {
		return CookieCsrfTokenRepository.withHttpOnlyFalse();
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, AccountService accounts,
			CsrfTokenRepository csrfTokens, SecurityContextRepository contexts) throws Exception {
		http.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
			.securityContext(sc -> sc.securityContextRepository(contexts))
			.formLogin(f -> f.disable())
			.httpBasic(b -> b.disable())
			.requestCache(c -> c.disable())
			.logout(l -> l.logoutUrl("/api/v1/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
			.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.addFilterAfter(new SessionRefreshFilter(accounts), SecurityContextHolderFilter.class)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/error", "/api/v1/health", "/actuator/health/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/articles", "/api/v1/articles/*", "/api/v1/categories",
						"/api/v1/publishers", "/api/v1/auth/csrf")
				.permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/verify-email",
						"/api/v1/auth/resend-verification", "/api/v1/auth/login", "/api/v1/auth/account/restore",
						"/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm")
				.permitAll()
				.requestMatchers("/api/v1/admin/**").hasRole(STAFF)
				// Reader-only features; staff accounts are kept separate (SRS §4.3).
				.requestMatchers("/api/v1/me/**").hasRole("READER")
				.anyRequest().authenticated());
		return http.build();
	}

	/** Readers get ROLE_READER. Staff get ROLE_STAFF plus ROLE_&lt;code&gt; for each staff role. */
	static UsernamePasswordAuthenticationToken authenticationFor(AuthPrincipal principal) {
		List<GrantedAuthority> authorities = new ArrayList<>();
		if (principal.isStaff()) {
			authorities.add(new SimpleGrantedAuthority("ROLE_" + STAFF));
			principal.staffRoles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
		}
		else {
			authorities.add(new SimpleGrantedAuthority("ROLE_READER"));
		}
		return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
	}

}
