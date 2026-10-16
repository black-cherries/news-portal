package org.newsportal.identity;

import java.util.Locale;
import java.util.regex.Pattern;

import org.newsportal.web.InvalidRequestException;

/** Email normalization and password rules. */
final class Credentials {

	static final int MIN_PASSWORD = 10;

	static final int MAX_PASSWORD = 128;

	// Deliberately simple: one @, no spaces, a dot in the domain. The verification email is the real check.
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

	private Credentials() {
	}

	static String normalizeEmail(String raw) {
		if (raw == null) {
			throw new InvalidRequestException("email is required");
		}
		String email = raw.strip().toLowerCase(Locale.ROOT);
		if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
			throw new InvalidRequestException("email is not valid");
		}
		return email;
	}

	/** Length-based policy (NIST SP 800-63B): long passphrases in any script are fine. */
	static void checkPassword(String password, String email) {
		if (password == null) {
			throw new InvalidRequestException("password is required");
		}
		int length = password.codePointCount(0, password.length());
		if (length < MIN_PASSWORD || length > MAX_PASSWORD) {
			throw new InvalidRequestException(
					"password must be between " + MIN_PASSWORD + " and " + MAX_PASSWORD + " characters");
		}
		if (password.strip().equalsIgnoreCase(email)) {
			throw new InvalidRequestException("password must not be the email address");
		}
	}

}
