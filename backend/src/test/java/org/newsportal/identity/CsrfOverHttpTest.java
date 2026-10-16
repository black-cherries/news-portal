package org.newsportal.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Checks the browser-facing CSRF contract over real HTTP (MockMvc's security
 * support replaces the token repository, so it cannot show this).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CsrfOverHttpTest {

	@LocalServerPort
	private int port;

	@Test
	void spaReadsTokenFromCookieAndSendsItAsHeader() throws Exception {
		CookieManager cookies = new CookieManager();
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
		String base = "http://127.0.0.1:" + port + "/api/v1/auth";

		HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/csrf")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(csrf.statusCode()).isEqualTo(200);
		assertThat(csrf.body()).contains("X-XSRF-TOKEN");
		String token = cookies.getCookieStore().getCookies().stream()
			.filter(c -> c.getName().equals("XSRF-TOKEN"))
			.findFirst()
			.orElseThrow()
			.getValue();

		String body = "{\"email\":\"nobody@example.org\",\"password\":\"wrong password here\"}";
		HttpResponse<String> withoutHeader = client.send(login(base, body).build(), HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> withHeader = client.send(login(base, body).header("X-XSRF-TOKEN", token).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(withoutHeader.statusCode()).isEqualTo(403);
		assertThat(withHeader.statusCode()).isEqualTo(401);
	}

	private static HttpRequest.Builder login(String base, String body) {
		return HttpRequest.newBuilder(URI.create(base + "/login"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body));
	}

}
