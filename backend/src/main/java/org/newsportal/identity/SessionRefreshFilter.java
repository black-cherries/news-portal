package org.newsportal.identity;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a session as soon as its account is disabled or its password changes,
 * and keeps the session's roles current. Runs on every authenticated request.
 */
class SessionRefreshFilter extends OncePerRequestFilter {

	private final AccountService accounts;

	SessionRefreshFilter(AccountService accounts) {
		this.accounts = accounts;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		SecurityContext context = SecurityContextHolder.getContext();
		Authentication auth = context.getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
			Optional<AuthPrincipal> current = accounts.refresh(principal.userId(), principal.securityStamp());
			if (current.isEmpty()) {
				SecurityContextHolder.clearContext();
				HttpSession session = request.getSession(false);
				if (session != null) {
					session.invalidate();
				}
			}
			else if (!current.get().equals(principal)) {
				context.setAuthentication(SecurityConfig.authenticationFor(current.get()));
			}
		}
		chain.doFilter(request, response);
	}

}
