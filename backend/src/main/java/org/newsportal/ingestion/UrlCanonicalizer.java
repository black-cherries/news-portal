package org.newsportal.ingestion;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Conservative URL normalization for duplicate detection (FR-012). Only
 * changes that cannot point to a different page are applied: lower-case
 * scheme and host, default port removed, fragment removed, known tracking
 * parameters removed. Path and remaining query are kept as-is.
 */
public final class UrlCanonicalizer {

	private static final Set<String> TRACKING_PARAMS = Set.of("fbclid", "gclid", "dclid", "msclkid", "mc_cid",
			"mc_eid", "igshid", "_ga", "ocid", "cmpid");

	private UrlCanonicalizer() {
	}

	/** Returns the canonical form, or empty if the value is not an absolute http(s) URL. */
	public static Optional<String> canonicalize(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		URI uri;
		try {
			uri = new URI(raw.strip());
		}
		catch (URISyntaxException ex) {
			return Optional.empty();
		}
		String scheme = (uri.getScheme() == null) ? null : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			return Optional.empty();
		}
		if (uri.getHost() == null || uri.getRawUserInfo() != null) {
			return Optional.empty();
		}
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		int port = uri.getPort();
		boolean defaultPort = port == -1 || ("http".equals(scheme) && port == 80)
				|| ("https".equals(scheme) && port == 443);
		String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) ? "/" : uri.getRawPath();

		StringBuilder out = new StringBuilder(scheme).append("://").append(host);
		if (!defaultPort) {
			out.append(':').append(port);
		}
		out.append(path);
		String query = stripTracking(uri.getRawQuery());
		if (!query.isEmpty()) {
			out.append('?').append(query);
		}
		return Optional.of(out.toString());
	}

	private static String stripTracking(String rawQuery) {
		if (rawQuery == null || rawQuery.isEmpty()) {
			return "";
		}
		List<String> kept = new ArrayList<>();
		for (String pair : rawQuery.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			String name = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
			if (!name.startsWith("utm_") && !TRACKING_PARAMS.contains(name)) {
				kept.add(pair);
			}
		}
		return String.join("&", kept);
	}

}
