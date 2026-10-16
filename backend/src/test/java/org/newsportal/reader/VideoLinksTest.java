package org.newsportal.reader;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.newsportal.reader.VideoLinks.Provider;
import org.newsportal.reader.VideoLinks.Recognized;

class VideoLinksTest {

	@Test
	void youtubeLinksUseTheNoCookiePlayer() {
		String embed = "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ";
		for (String url : new String[] { "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
				"https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ", "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
				"https://youtu.be/dQw4w9WgXcQ", "https://www.youtube.com/embed/dQw4w9WgXcQ",
				"https://www.youtube.com/shorts/dQw4w9WgXcQ", "http://www.youtube-nocookie.com/embed/dQw4w9WgXcQ" }) {
			assertThat(VideoLinks.recognize(url, null)).as(url).isEqualTo(new Recognized(Provider.YOUTUBE, embed));
		}
	}

	@Test
	void vimeoLinksUseDoNotTrack() {
		assertThat(VideoLinks.recognize("https://vimeo.com/76979871", null))
			.isEqualTo(new Recognized(Provider.VIMEO, "https://player.vimeo.com/video/76979871?dnt=1"));
		assertThat(VideoLinks.recognize("https://player.vimeo.com/video/76979871", null).provider())
			.isEqualTo(Provider.VIMEO);
	}

	@Test
	void videoFilesPlayOnlyOverHttps() {
		assertThat(VideoLinks.recognize("https://cdn.example.org/clip.mp4", null))
			.isEqualTo(new Recognized(Provider.FILE, "https://cdn.example.org/clip.mp4"));
		assertThat(VideoLinks.recognize("https://cdn.example.org/stream", "video/webm").provider())
			.isEqualTo(Provider.FILE);
		assertThat(VideoLinks.recognize("http://cdn.example.org/clip.mp4", null).provider()).isEqualTo(Provider.LINK);
		// A type we cannot play is only linked.
		assertThat(VideoLinks.recognize("https://cdn.example.org/clip.mov", "video/quicktime").provider())
			.isEqualTo(Provider.LINK);
	}

	@Test
	void everythingElseIsOnlyLinked() {
		for (String url : new String[] { "https://example.org/video-page", "javascript:alert(1)",
				"https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ", "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
				"https://www.youtube.com/watch?v=short", "https://vimeo.com/channels/staffpicks", "not a url" }) {
			assertThat(VideoLinks.recognize(url, null)).as(url).isEqualTo(new Recognized(Provider.LINK, null));
		}
	}

}
