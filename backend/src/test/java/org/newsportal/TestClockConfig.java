package org.newsportal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Test profile only: replaces the system clock with one tests can move. */
@Configuration
@Profile("test")
public class TestClockConfig {

	@Bean
	@Primary
	MutableClock testClock() {
		return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
	}

	public static final class MutableClock extends Clock {

		private volatile Instant now;

		MutableClock(Instant start) {
			this.now = start;
		}

		public void set(Instant instant) {
			this.now = instant;
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
