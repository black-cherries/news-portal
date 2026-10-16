package org.newsportal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.newsportal.TestClockConfig.MutableClock;
import org.newsportal.article.Article;
import org.newsportal.article.ArticleContentRepository;
import org.newsportal.article.ArticleRepository;
import org.newsportal.ingestion.QuarantinedRecord.Reason;
import org.newsportal.ingestion.StubFeedServer.Response;
import org.newsportal.source.ContentUse;
import org.newsportal.source.NewsSource;
import org.newsportal.source.RightsReview;
import org.newsportal.source.RightsStatus;
import org.newsportal.source.SourceRightsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs real fetch → parse → store cycles against a local stub server and the
 * test database. Not @Transactional: each entry is stored in its own transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestionServiceTest {

	private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

	@Autowired
	private IngestionService ingestion;

	@Autowired
	private SourceFeedService feedService;

	@Autowired
	private SourceRightsService rights;

	@Autowired
	private SourceFeedRepository feeds;

	@Autowired
	private ArticleRepository articles;

	@Autowired
	private ArticleContentRepository contents;

	@Autowired
	private QuarantinedRecordRepository quarantine;

	@Autowired
	private MutableClock clock;

	private StubFeedServer server;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(START);
		server = new StubFeedServer();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	void blockedSourceIsNeverContacted() {
		NewsSource source = rights.proposeSource(code(), "Unreviewed", "https://x.example", "en");
		SourceFeed feed = feedService.addFeed(source.getId(), server.url("/feed"), 30);
		server.respond("/feed", Response.ok(rss(item("a", "https://x.example/a", "A"))));

		IngestionJob job = ingestion.runFeed(feed.getId());

		assertThat(job.getOutcome()).isEqualTo(FeedOutcome.BLOCKED_BY_RIGHTS);
		assertThat(server.hits("/feed")).isZero();
		assertThat(articles.findBySourceId(source.getId())).isEmpty();
		assertThat(feeds.findById(feed.getId()).orElseThrow().getConsecutiveFailures()).isZero();
	}

	@Test
	void refetchingTheSameFeedDoesNotCreateDuplicates() {
		SourceFeed feed = activeFeed(false);
		server.respond("/feed", Response.ok(rss(item("g1", "https://pub.example/1", "One"),
				item("g2", "https://pub.example/2", "Two"))));

		IngestionJob first = ingestion.runFeed(feed.getId());
		IngestionJob second = ingestion.runFeed(feed.getId());

		assertThat(first.getOutcome()).isEqualTo(FeedOutcome.SUCCESS);
		assertThat(first.getItemsCreated()).isEqualTo(2);
		assertThat(second.getOutcome()).isEqualTo(FeedOutcome.NO_NEW_ITEMS);
		assertThat(second.getItemsUnchanged()).isEqualTo(2);
		assertThat(articles.findBySourceId(sourceId(feed))).hasSize(2);
	}

	@Test
	void gzipCompressedFeedsAreRead() throws Exception {
		// Some publishers (e.g. UN News) compress even when the client did not ask.
		SourceFeed feed = activeFeed(false);
		java.io.ByteArrayOutputStream zipped = new java.io.ByteArrayOutputStream();
		try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(zipped)) {
			gz.write(rss(item("z1", "https://pub.example/z1", "Compressed")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		server.respond("/feed", new Response(200,
				Map.of("Content-Type", "application/rss+xml", "Content-Encoding", "gzip"), zipped.toByteArray()));

		IngestionJob job = ingestion.runFeed(feed.getId());

		assertThat(job.getOutcome()).isEqualTo(FeedOutcome.SUCCESS);
		assertThat(job.getItemsCreated()).isEqualTo(1);
	}

	@Test
	void entriesWithoutGuidAreMatchedByCanonicalUrl() {
		SourceFeed feed = activeFeed(false);
		server.respond("/feed", Response.ok(rss(item(null, "https://pub.example/story?id=5", "Story"))));
		ingestion.runFeed(feed.getId());

		server.respond("/feed",
				Response.ok(rss(item(null, "https://PUB.example/story?id=5&utm_source=rss#top", "Story"))));
		IngestionJob job = ingestion.runFeed(feed.getId());

		assertThat(job.getItemsCreated()).isZero();
		assertThat(articles.findBySourceId(sourceId(feed))).singleElement()
			.extracting(Article::getCanonicalUrl)
			.isEqualTo("https://pub.example/story?id=5");
	}

	@Test
	void changedEntryUpdatesTheExistingArticle() {
		SourceFeed feed = activeFeed(false);
		server.respond("/feed", Response.ok(rss(item("g1", "https://pub.example/1", "Draft headline"))));
		ingestion.runFeed(feed.getId());

		clock.set(START.plus(Duration.ofHours(1)));
		server.respond("/feed", Response.ok(rss(item("g1", "https://pub.example/1", "Corrected headline"))));
		IngestionJob job = ingestion.runFeed(feed.getId());

		assertThat(job.getOutcome()).isEqualTo(FeedOutcome.SUCCESS);
		assertThat(job.getItemsUpdated()).isEqualTo(1);
		Article article = articles.findBySourceId(sourceId(feed)).getFirst();
		assertThat(article.getTitle()).isEqualTo("Corrected headline");
		assertThat(article.getFirstIngestedAt()).isEqualTo(START);
		assertThat(article.getLastIngestedAt()).isEqualTo(START.plus(Duration.ofHours(1)));
	}

	@Test
	void validEmptyFeedIsNotAFailure() {
		SourceFeed feed = activeFeed(false);
		server.respond("/feed", Response.ok(rss()));

		IngestionJob job = ingestion.runFeed(feed.getId());

		assertThat(job.getOutcome()).isEqualTo(FeedOutcome.NO_NEW_ITEMS);
		assertThat(job.getOutcome().isFailure()).isFalse();
		SourceFeed after = feeds.findById(feed.getId()).orElseThrow();
		assertThat(after.getConsecutiveFailures()).isZero();
		assertThat(after.getLastSuccessAt()).isEqualTo(START);
	}

	@Test
	void failureKindsAreDistinguished() {
		assertThat(outcomeFor(Response.status(401))).isEqualTo(FeedOutcome.AUTH_FAILURE);
		assertThat(outcomeFor(Response.status(403))).isEqualTo(FeedOutcome.AUTH_FAILURE);
		assertThat(outcomeFor(Response.status(404))).isEqualTo(FeedOutcome.NOT_FOUND);
		assertThat(outcomeFor(Response.status(410))).isEqualTo(FeedOutcome.NOT_FOUND);
		assertThat(outcomeFor(Response.status(500))).isEqualTo(FeedOutcome.HTTP_ERROR);
		assertThat(outcomeFor(Response.status(429))).isEqualTo(FeedOutcome.RATE_LIMITED);
		assertThat(outcomeFor(Response.ok("<html><body>Maintenance</body></html>"))).isEqualTo(FeedOutcome.MALFORMED);
		assertThat(outcomeFor(Response.ok("x".repeat(70_000)))).isEqualTo(FeedOutcome.TOO_LARGE);
	}

	@Test
	void unreachableHostIsANetworkError() throws Exception {
		NewsSource source = activeSource(false);
		String url;
		try (StubFeedServer closed = new StubFeedServer()) {
			url = closed.url("/feed");
		}
		SourceFeed feed = feedService.addFeed(source.getId(), url, 30);

		assertThat(ingestion.runFeed(feed.getId()).getOutcome()).isEqualTo(FeedOutcome.NETWORK_ERROR);
	}

	@Test
	void repeatedFailuresBackOffUpToTheCap() {
		SourceFeed feed = activeFeed(false);
		server.respond("/feed", Response.status(503));

		List<Duration> delays = new java.util.ArrayList<>();
		for (int i = 0; i < 6; i++) {
			ingestion.runFeed(feed.getId());
			delays.add(Duration.between(START, feeds.findById(feed.getId()).orElseThrow().getNextRunAt()));
		}

		assertThat(delays).containsExactly(Duration.ofMinutes(30), Duration.ofMinutes(60), Duration.ofMinutes(120),
				Duration.ofMinutes(240), Duration.ofHours(6), Duration.ofHours(6));
		assertThat(server.hits("/feed")).isEqualTo(6);
		assertThat(feeds.findById(feed.getId()).orElseThrow().getConsecutiveFailures()).isEqualTo(6);

		server.respond("/feed", Response.ok(rss()));
		ingestion.runFeed(feed.getId());
		assertThat(feeds.findById(feed.getId()).orElseThrow().getConsecutiveFailures()).isZero();
	}

	@Test
	void rateLimitHonoursRetryAfter() {
		SourceFeed feed = activeFeed(false);
		server.respond("/feed", new Response(429, Map.of("Retry-After", "7200"), new byte[0]));

		ingestion.runFeed(feed.getId());

		assertThat(feeds.findById(feed.getId()).orElseThrow().getNextRunAt()).isEqualTo(START.plus(Duration.ofHours(2)));
	}

	@Test
	void conditionalRequestUsesEtagAndNotModifiedIsNotAFailure() {
		SourceFeed feed = activeFeed(false);
		server.respond("/feed", new Response(200, Map.of("ETag", "\"v1\""), rss(item("g", "https://pub.example/g", "G"))
			.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		ingestion.runFeed(feed.getId());

		server.respond("/feed", Response.status(304));
		IngestionJob job = ingestion.runFeed(feed.getId());

		assertThat(server.lastRequestHeaders().getFirst("If-None-Match")).isEqualTo("\"v1\"");
		assertThat(job.getOutcome()).isEqualTo(FeedOutcome.NOT_MODIFIED);
		assertThat(feeds.findById(feed.getId()).orElseThrow().getConsecutiveFailures()).isZero();
	}

	@Test
	void invalidEntriesAreQuarantinedWhileValidOnesAreStored() {
		SourceFeed feed = activeFeed(false);
		server.respond("/feed", Response.ok(rss(item("ok", "https://pub.example/ok", "Valid"),
				item("nolink", null, "No link"), item("js", "javascript:alert(1)", "Bad link"),
				item("notitle", "https://pub.example/nt", ""))));

		IngestionJob job = ingestion.runFeed(feed.getId());

		assertThat(job.getOutcome()).isEqualTo(FeedOutcome.SUCCESS);
		assertThat(job.getItemsCreated()).isEqualTo(1);
		assertThat(job.getItemsQuarantined()).isEqualTo(3);
		assertThat(quarantine.findByJobId(job.getId())).extracting(QuarantinedRecord::getReason)
			.containsExactlyInAnyOrder(Reason.MISSING_LINK, Reason.INVALID_LINK, Reason.MISSING_TITLE);
	}

	@Test
	void unknownPublicationTimeStaysUnknown() {
		SourceFeed feed = activeFeed(false);
		server.respond("/feed", Response.ok(rss(item("g", "https://pub.example/g", "Undated"))));

		ingestion.runFeed(feed.getId());

		Article article = articles.findBySourceId(sourceId(feed)).getFirst();
		assertThat(article.getPublishedAt()).isNull();
		assertThat(article.getPublisherUpdatedAt()).isNull();
		assertThat(article.getFirstIngestedAt()).isEqualTo(START);
		assertThat(article.getLanguage()).isEqualTo("en");
	}

	@Test
	void samePageFromTwoPublishersStaysTwoArticles() {
		SourceFeed a = activeFeed(false);
		SourceFeed b = activeFeed(false);
		String body = rss(item("wire-1", "https://wire.example/story", "Wire story"));
		server.respond("/feed", Response.ok(body));

		ingestion.runFeed(a.getId());
		ingestion.runFeed(b.getId());

		assertThat(articles.findBySourceId(sourceId(a))).hasSize(1);
		assertThat(articles.findBySourceId(sourceId(b))).hasSize(1);
	}

	@Test
	void fullTextIsStoredOnlyWhileStorageIsPermitted() {
		NewsSource source = activeSource(false);
		SourceFeed feed = feedService.addFeed(source.getId(), server.url("/feed"), 30);
		server.respond("/feed", Response.ok(rss(itemWithContent("g", "https://pub.example/g", "T",
				"<p>Body</p><script>alert(1)</script>"))));

		ingestion.runFeed(feed.getId());
		Long articleId = articles.findBySourceId(source.getId()).getFirst().getId();
		assertThat(contents.findById(articleId)).isEmpty();

		rights.recordRightsDecision(source.getId(), ContentUse.STORAGE, permitted());
		ingestion.runFeed(feed.getId());
		assertThat(contents.findById(articleId)).get()
			.satisfies(c -> assertThat(c.getBodyHtml()).contains("Body").doesNotContain("script"));

		rights.recordRightsDecision(source.getId(), ContentUse.STORAGE,
				new RightsReview(RightsStatus.DENIED, null, null, null, "reviewer", "Licence withdrawn", null, null));
		ingestion.runFeed(feed.getId());
		assertThat(contents.findById(articleId)).isEmpty();
	}

	@Test
	void oneFailingFeedDoesNotStopTheOthers() {
		SourceFeed broken = activeFeed(false);
		NewsSource source = activeSource(false);
		SourceFeed healthy = feedService.addFeed(source.getId(), server.url("/healthy"), 30);
		server.respond("/feed", Response.status(500));
		server.respond("/healthy", Response.ok(rss(item("h", "https://pub.example/h", "H"))));

		new IngestionScheduler(feeds, ingestion, clock).runDueFeeds();

		assertThat(feeds.findById(broken.getId()).orElseThrow().getLastOutcome()).isEqualTo(FeedOutcome.HTTP_ERROR);
		assertThat(feeds.findById(healthy.getId()).orElseThrow().getLastOutcome()).isEqualTo(FeedOutcome.SUCCESS);
	}

	// --- helpers ---

	private FeedOutcome outcomeFor(Response response) {
		String path = "/f-" + UUID.randomUUID();
		server.respond(path, response);
		SourceFeed feed = feedService.addFeed(activeSource(false).getId(), server.url(path), 30);
		return ingestion.runFeed(feed.getId()).getOutcome();
	}

	private SourceFeed activeFeed(boolean storage) {
		NewsSource source = activeSource(storage);
		return feedService.addFeed(source.getId(), server.url("/feed"), 30);
	}

	private NewsSource activeSource(boolean storage) {
		NewsSource source = rights.proposeSource(code(), "Publisher", "https://pub.example", "en");
		rights.recordRightsDecision(source.getId(), ContentUse.METADATA, permitted());
		if (storage) {
			rights.recordRightsDecision(source.getId(), ContentUse.STORAGE, permitted());
		}
		return rights.activateSource(source.getId(), "manager", "Approved for test");
	}

	private Long sourceId(SourceFeed feed) {
		return feeds.findById(feed.getId()).map(f -> f.getSource().getId()).orElseThrow();
	}

	private static RightsReview permitted() {
		return new RightsReview(RightsStatus.PERMITTED, null, null, null, "reviewer", "Licence allows it", null,
				LocalDate.of(2026, 12, 31));
	}

	private static String code() {
		return "src-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static String rss(String... items) {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
				<channel><title>Pub</title><link>https://pub.example/</link><description>d</description>
				%s
				</channel></rss>
				""".formatted(String.join("\n", items));
	}

	private static String item(String guid, String link, String title) {
		return itemWithContent(guid, link, title, null);
	}

	private static String itemWithContent(String guid, String link, String title, String content) {
		StringBuilder sb = new StringBuilder("<item>");
		sb.append("<title>").append(escape(title)).append("</title>");
		if (link != null) {
			sb.append("<link>").append(escape(link)).append("</link>");
		}
		if (guid != null) {
			sb.append("<guid isPermaLink=\"false\">").append(escape(guid)).append("</guid>");
		}
		if (content != null) {
			sb.append("<content:encoded><![CDATA[").append(content).append("]]></content:encoded>");
		}
		return sb.append("</item>").toString();
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

}
