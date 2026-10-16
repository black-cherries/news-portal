package org.newsportal.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.newsportal.ingestion.FeedParser.FeedEntry;
import org.newsportal.ingestion.FeedParser.MalformedFeedException;
import org.newsportal.ingestion.FeedParser.ParsedFeed;

class FeedParserTest {

	private final FeedParser parser = new FeedParser();

	@Test
	void parsesRssEntriesWithSeparateTimestampsAndCategories() throws Exception {
		ParsedFeed feed = parser.parse(bytes("""
				<?xml version="1.0" encoding="UTF-8"?>
				<rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
				  <channel>
				    <title>Example</title><link>https://example.org/</link><description>d</description>
				    <language>hi-IN</language>
				    <item>
				      <title>पहली खबर</title>
				      <link>https://example.org/a</link>
				      <guid isPermaLink="false">story-1</guid>
				      <description>&lt;p&gt;Short &lt;b&gt;summary&lt;/b&gt;&lt;/p&gt;</description>
				      <content:encoded><![CDATA[<p>Full text</p>]]></content:encoded>
				      <category>Politics</category><category>Politics</category>
				      <pubDate>Thu, 01 Jan 2026 10:00:00 GMT</pubDate>
				    </item>
				    <item><title>No date</title><link>https://example.org/b</link></item>
				  </channel>
				</rss>
				"""));

		assertThat(feed.language()).isEqualTo("hi");
		assertThat(feed.entries()).hasSize(2);
		FeedEntry first = feed.entries().getFirst();
		assertThat(first.guid()).isEqualTo("story-1");
		assertThat(first.title()).isEqualTo("पहली खबर");
		assertThat(first.contentHtml()).contains("Full text");
		assertThat(first.categories()).containsExactly("Politics");
		assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
		assertThat(feed.entries().get(1).publishedAt()).isNull();
	}

	@Test
	void collectsVideoLinksFromEnclosuresMediaRssAndVideoPages() throws Exception {
		ParsedFeed feed = parser.parse(bytes("""
				<?xml version="1.0" encoding="UTF-8"?>
				<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
				  <channel>
				    <title>Example</title><link>https://example.org/</link><description>d</description>
				    <item>
				      <title>With videos</title>
				      <link>https://example.org/a</link>
				      <enclosure url="https://cdn.example.org/clip.mp4" type="video/mp4" length="1000"/>
				      <media:content url="https://cdn.example.org/photo.jpg" medium="image"/>
				      <media:group>
				        <media:content url="https://cdn.example.org/clip.webm" type="video/webm; codecs=vp9"/>
				        <media:player url="https://www.youtube.com/watch?v=dQw4w9WgXcQ"/>
				      </media:group>
				      <media:player url="https://example.org/video-page"/>
				      <media:content url="javascript:alert(1)" medium="video"/>
				    </item>
				    <item>
				      <title>A video page</title>
				      <link>https://youtu.be/dQw4w9WgXcQ</link>
				      <enclosure url="https://cdn.example.org/a.mp3" type="audio/mpeg" length="1"/>
				    </item>
				    <item><title>No video</title><link>https://example.org/c</link></item>
				  </channel>
				</rss>
				"""));

		assertThat(feed.entries().get(0).videos()).containsExactly(
				new FeedParser.FeedVideo("https://cdn.example.org/clip.mp4", "video/mp4"),
				new FeedParser.FeedVideo("https://cdn.example.org/clip.webm", "video/webm"),
				new FeedParser.FeedVideo("https://www.youtube.com/watch?v=dQw4w9WgXcQ", null));
		assertThat(feed.entries().get(1).videos())
			.containsExactly(new FeedParser.FeedVideo("https://youtu.be/dQw4w9WgXcQ", null));
		assertThat(feed.entries().get(2).videos()).isEmpty();
	}

	@Test
	void picksAnHttpsLeadImage() throws Exception {
		ParsedFeed feed = parser.parse(bytes("""
				<?xml version="1.0" encoding="UTF-8"?>
				<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
				  <channel>
				    <title>Example</title><link>https://example.org/</link><description>d</description>
				    <item>
				      <title>Thumbnail wins</title><link>https://example.org/a</link>
				      <media:content url="https://cdn.example.org/big.jpg" medium="image"/>
				      <media:thumbnail url="https://cdn.example.org/thumb.jpg"/>
				    </item>
				    <item>
				      <title>Grouped image</title><link>https://example.org/b</link>
				      <media:group><media:content url="https://cdn.example.org/g.png" type="image/png"/></media:group>
				    </item>
				    <item>
				      <title>Enclosure</title><link>https://example.org/c</link>
				      <enclosure url="https://cdn.example.org/e.webp" type="image/webp" length="1"/>
				    </item>
				    <item>
				      <title>Plain HTTP is ignored</title><link>https://example.org/d</link>
				      <media:thumbnail url="http://cdn.example.org/insecure.jpg"/>
				    </item>
				  </channel>
				</rss>
				"""));

		assertThat(feed.entries()).extracting(FeedEntry::imageUrl).containsExactly(
				"https://cdn.example.org/thumb.jpg", "https://cdn.example.org/g.png", "https://cdn.example.org/e.webp",
				null);
	}

	@Test
	void parsesAtom() throws Exception {
		ParsedFeed feed = parser.parse(bytes("""
				<?xml version="1.0" encoding="utf-8"?>
				<feed xmlns="http://www.w3.org/2005/Atom" xml:lang="ar">
				  <title>Example</title><id>urn:x</id><updated>2026-01-02T00:00:00Z</updated>
				  <entry>
				    <title>خبر</title><id>urn:story:9</id>
				    <link href="https://example.org/ar/9"/>
				    <published>2026-01-01T08:00:00Z</published>
				    <updated>2026-01-02T09:30:00Z</updated>
				  </entry>
				</feed>
				"""));
		FeedEntry entry = feed.entries().getFirst();
		assertThat(entry.guid()).isEqualTo("urn:story:9");
		assertThat(entry.link()).isEqualTo("https://example.org/ar/9");
		assertThat(entry.publishedAt()).isEqualTo(Instant.parse("2026-01-01T08:00:00Z"));
		assertThat(entry.updatedAt()).isEqualTo(Instant.parse("2026-01-02T09:30:00Z"));
	}

	@Test
	void rejectsMalformedXml() {
		assertThatThrownBy(() -> parser.parse(bytes("<rss><channel><item>")))
			.isInstanceOf(MalformedFeedException.class);
		assertThatThrownBy(() -> parser.parse(bytes("<html><body>Not a feed</body></html>")))
			.isInstanceOf(MalformedFeedException.class);
	}

	@Test
	void rejectsDoctypeToPreventXxe() {
		String xxe = """
				<?xml version="1.0"?>
				<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///C:/Windows/win.ini">]>
				<rss version="2.0"><channel><title>&xxe;</title><link>https://e.org</link><description>d</description></channel></rss>
				""";
		assertThatThrownBy(() -> parser.parse(bytes(xxe))).isInstanceOf(MalformedFeedException.class);
	}

	@Test
	void normalizesLanguageTags() {
		assertThat(FeedParser.normalizeLanguage("en-US")).isEqualTo("en");
		assertThat(FeedParser.normalizeLanguage("UR_pk")).isEqualTo("ur");
		assertThat(FeedParser.normalizeLanguage("")).isNull();
		assertThat(FeedParser.normalizeLanguage("??")).isNull();
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

}
