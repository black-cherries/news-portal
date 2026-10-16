package org.newsportal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlCanonicalizerTest {

	@Test
	void normalizesSchemeHostPortAndFragment() {
		assertThat(UrlCanonicalizer.canonicalize("HTTPS://News.Example.ORG:443/World/Story-1#comments"))
			.contains("https://news.example.org/World/Story-1");
		assertThat(UrlCanonicalizer.canonicalize("http://example.org")).contains("http://example.org/");
		assertThat(UrlCanonicalizer.canonicalize("http://example.org:8080/a")).contains("http://example.org:8080/a");
	}

	@Test
	void removesOnlyTrackingParameters() {
		assertThat(UrlCanonicalizer.canonicalize("https://example.org/a?id=7&utm_source=rss&utm_medium=feed&fbclid=x"))
			.contains("https://example.org/a?id=7");
		assertThat(UrlCanonicalizer.canonicalize("https://example.org/a?utm_campaign=z"))
			.contains("https://example.org/a");
	}

	@Test
	void keepsPathCaseAndMeaningfulQueryOrder() {
		assertThat(UrlCanonicalizer.canonicalize("https://example.org/Path/?b=2&a=1"))
			.contains("https://example.org/Path/?b=2&a=1");
	}

	@Test
	void rejectsNonHttpAndRelativeUrls() {
		assertThat(UrlCanonicalizer.canonicalize("javascript:alert(1)")).isEmpty();
		assertThat(UrlCanonicalizer.canonicalize("ftp://example.org/file")).isEmpty();
		assertThat(UrlCanonicalizer.canonicalize("/relative/path")).isEmpty();
		assertThat(UrlCanonicalizer.canonicalize("https://user:pw@example.org/")).isEmpty();
		assertThat(UrlCanonicalizer.canonicalize("not a url")).isEmpty();
		assertThat(UrlCanonicalizer.canonicalize(null)).isEmpty();
	}

}
