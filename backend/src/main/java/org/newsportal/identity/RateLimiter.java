package org.newsportal.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window limiter for abuse-prone endpoints (FR-041). Fine
 * for a single server; a shared store is needed if the API is ever scaled out.
 */
@Component
public class RateLimiter {

	private static final int MAX_KEYS = 50_000;

	private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

	private final Clock clock;

	public RateLimiter(Clock clock) {
		this.clock = clock;
	}

	/** Records an attempt; throws if {@code limit} attempts already happened within {@code window}. */
	public void acquire(String key, int limit, Duration window) {
		Instant now = clock.instant();
		if (hits.size() > MAX_KEYS) {
			evictExpired(now, Duration.ofHours(24));
		}
		Deque<Instant> times = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
		synchronized (times) {
			Instant cutoff = now.minus(window);
			while (!times.isEmpty() && !times.peekFirst().isAfter(cutoff)) {
				times.pollFirst();
			}
			if (times.size() >= limit) {
				Duration retryAfter = Duration.between(now, times.peekFirst().plus(window));
				throw new RateLimitedException(retryAfter.isNegative() ? Duration.ZERO : retryAfter);
			}
			times.addLast(now);
		}
	}

	/** Test support: forget all recorded attempts. */
	public void reset() {
		hits.clear();
	}

	private void evictExpired(Instant now, Duration maxWindow) {
		Instant cutoff = now.minus(maxWindow);
		hits.entrySet().removeIf(e -> {
			synchronized (e.getValue()) {
				return e.getValue().isEmpty() || !e.getValue().peekLast().isAfter(cutoff);
			}
		});
	}

	public static class RateLimitedException extends RuntimeException {

		private final Duration retryAfter;

		RateLimitedException(Duration retryAfter) {
			super("Too many attempts");
			this.retryAfter = retryAfter;
		}

		public Duration getRetryAfter() {
			return retryAfter;
		}

	}

}
