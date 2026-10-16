package org.newsportal.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.zip.GZIPInputStream;

import org.springframework.stereotype.Component;

/** Fetches a feed over HTTP and classifies the response (FR-015). Makes exactly one request per call. */
@Component
public class FeedFetcher {

	private final HttpClient client;

	private final IngestionProperties props;

	public FeedFetcher(IngestionProperties props) {
		this.props = props;
		this.client = HttpClient.newBuilder()
			.connectTimeout(props.connectTimeout())
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	}

	public FetchResult fetch(String feedUrl, String etag, String lastModified, Instant now) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(feedUrl))
			.timeout(props.requestTimeout())
			.header("User-Agent", props.userAgent())
			.header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8")
			.header("Accept-Encoding", "gzip")
			.GET();
		if (etag != null) {
			request.header("If-None-Match", etag);
		}
		if (lastModified != null) {
			request.header("If-Modified-Since", lastModified);
		}

		HttpResponse<InputStream> response;
		try {
			response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (HttpTimeoutException ex) {
			return FetchResult.failed(FeedOutcome.NETWORK_ERROR, null, "Timed out");
		}
		catch (IOException ex) {
			return FetchResult.failed(FeedOutcome.NETWORK_ERROR, null, ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return FetchResult.failed(FeedOutcome.NETWORK_ERROR, null, "Interrupted");
		}

		int status = response.statusCode();
		try (InputStream body = response.body()) {
			if (status == 304) {
				return new FetchResult(FeedOutcome.NOT_MODIFIED, status, null, etag, lastModified, null, null);
			}
			if (status == 401 || status == 403) {
				return FetchResult.failed(FeedOutcome.AUTH_FAILURE, status, "HTTP " + status);
			}
			if (status == 404 || status == 410) {
				return FetchResult.failed(FeedOutcome.NOT_FOUND, status, "HTTP " + status);
			}
			if (status == 429) {
				Duration retryAfter = parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null), now);
				return new FetchResult(FeedOutcome.RATE_LIMITED, status, null, null, null, retryAfter, "HTTP 429");
			}
			if (status < 200 || status >= 300) {
				return FetchResult.failed(FeedOutcome.HTTP_ERROR, status, "HTTP " + status);
			}
			// Some servers compress even when not asked; the size limit applies after decompression.
			boolean gzip = response.headers()
				.firstValue("Content-Encoding")
				.map(v -> v.strip().equalsIgnoreCase("gzip"))
				.orElse(false);
			InputStream content = gzip ? new GZIPInputStream(body) : body;
			byte[] bytes = content.readNBytes(props.maxFeedBytes() + 1);
			if (bytes.length > props.maxFeedBytes()) {
				return FetchResult.failed(FeedOutcome.TOO_LARGE, status,
						"Response exceeds " + props.maxFeedBytes() + " bytes");
			}
			return new FetchResult(null, status, bytes, response.headers().firstValue("ETag").orElse(null),
					response.headers().firstValue("Last-Modified").orElse(null), null, null);
		}
		catch (IOException ex) {
			return FetchResult.failed(FeedOutcome.NETWORK_ERROR, status, "Reading body: " + ex.getMessage());
		}
	}

	static Duration parseRetryAfter(String value, Instant now) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
		}
		catch (NumberFormatException ignored) {
			// Not delta-seconds; try an HTTP date.
		}
		try {
			Instant at = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
			return at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO;
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	/**
	 * Fetch result. {@code outcome} is null when the body was downloaded and
	 * still needs parsing.
	 */
	public record FetchResult(FeedOutcome outcome, Integer httpStatus, byte[] body, String etag, String lastModified,
			Duration retryAfter, String diagnostic) {

		static FetchResult failed(FeedOutcome outcome, Integer httpStatus, String diagnostic) {
			return new FetchResult(outcome, httpStatus, null, null, null, null, diagnostic);
		}

	}

}
