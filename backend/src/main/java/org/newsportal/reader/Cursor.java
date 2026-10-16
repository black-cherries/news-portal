package org.newsportal.reader;

import org.newsportal.web.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/** Opaque keyset-pagination position: the last item's sort time and id. */
public record Cursor(Instant sortAt, long id) {

	public String encode() {
		String raw = sortAt.toString() + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static Cursor decode(String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
			String[] parts = raw.split("\\|", 2);
			return new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
		}
		catch (IllegalArgumentException | DateTimeParseException | ArrayIndexOutOfBoundsException ex) {
			throw new InvalidRequestException("invalid cursor");
		}
	}

}
