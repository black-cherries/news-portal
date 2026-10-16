package org.newsportal.reader;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a publisher's video link into a privacy-friendlier embed address,
 * for a short list of known providers (FR-056, FR-057). Anything else is
 * shown as a link to the original video.
 *
 * - YouTube: the youtube-nocookie.com player.
 * - Vimeo: the player with "do not track" set.
 * - Direct video files (MP4, WebM, Ogg) over HTTPS: played in a video element.
 */
public final class VideoLinks {

	private VideoLinks() {
	}

	public enum Provider {
		YOUTUBE, VIMEO, FILE, LINK
	}

	public record Recognized(Provider provider, String embedUrl) {
	}

	private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

	private static final Pattern YOUTUBE_PATH = Pattern.compile("^/(?:embed|shorts|live|v)/([A-Za-z0-9_-]{11})(?:/.*)?$");

	private static final Pattern VIMEO_PATH = Pattern.compile("^/(?:video/)?(\\d{1,12})(?:/.*)?$");

	private static final Pattern YOUTUBE_V = Pattern.compile("(?:^|&)v=([A-Za-z0-9_-]{11})(?:&|$)");

	/** @param mimeType from the feed, may be null */
	public static Recognized recognize(String url, String mimeType) {
		URI uri;
		try {
			uri = URI.create(url.strip());
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			return new Recognized(Provider.LINK, null);
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		if (!scheme.equals("https") && !scheme.equals("http")) {
			return new Recognized(Provider.LINK, null);
		}

		Optional<String> youtube = youtubeId(host, path, uri.getRawQuery());
		if (youtube.isPresent()) {
			return new Recognized(Provider.YOUTUBE, "https://www.youtube-nocookie.com/embed/" + youtube.get());
		}
		if (host.equals("vimeo.com") || host.equals("www.vimeo.com") || host.equals("player.vimeo.com")) {
			Matcher m = VIMEO_PATH.matcher(path);
			if (m.matches()) {
				return new Recognized(Provider.VIMEO, "https://player.vimeo.com/video/" + m.group(1) + "?dnt=1");
			}
		}
		// A file is played straight from the publisher's server, so it must be HTTPS.
		if (scheme.equals("https") && isVideoFile(path, mimeType)) {
			return new Recognized(Provider.FILE, uri.toString());
		}
		return new Recognized(Provider.LINK, null);
	}

	/** Whether the feed points at a video at all. */
	public static boolean isVideo(String url, String mimeType) {
		if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("video/")) {
			return true;
		}
		return url != null && recognize(url, mimeType).provider() != Provider.LINK;
	}

	private static Optional<String> youtubeId(String host, String path, String query) {
		if (host.equals("youtu.be")) {
			String id = path.startsWith("/") ? path.substring(1) : path;
			return YOUTUBE_ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
		}
		if (!(host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtube-nocookie.com")
				|| host.endsWith(".youtube-nocookie.com"))) {
			return Optional.empty();
		}
		if (path.equals("/watch") && query != null) {
			Matcher m = YOUTUBE_V.matcher(query);
			return m.find() ? Optional.of(m.group(1)) : Optional.empty();
		}
		Matcher m = YOUTUBE_PATH.matcher(path);
		return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
	}

	private static boolean isVideoFile(String path, String mimeType) {
		String type = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
		if (type.equals("video/mp4") || type.equals("video/webm") || type.equals("video/ogg")) {
			return true;
		}
		String p = path.toLowerCase(Locale.ROOT);
		return type.isEmpty() && (p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".ogv"));
	}

}
