package org.newsportal.ingestion;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

/** Local HTTP server that serves canned feed responses and counts requests. */
public final class StubFeedServer implements AutoCloseable {

	public record Response(int status, Map<String, String> headers, byte[] body) {

		public static Response ok(String body) {
			return new Response(200, Map.of("Content-Type", "application/rss+xml; charset=utf-8"),
					body.getBytes(StandardCharsets.UTF_8));
		}

		public static Response status(int status) {
			return new Response(status, Map.of(), new byte[0]);
		}

	}

	private final HttpServer server;

	private final Map<String, Response> responses = new ConcurrentHashMap<>();

	private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

	private volatile Headers lastRequestHeaders;

	public StubFeedServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
			lastRequestHeaders = exchange.getRequestHeaders();
			Response r = responses.getOrDefault(path, Response.status(404));
			r.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
			boolean noBody = r.status() == 304 || r.body().length == 0;
			exchange.sendResponseHeaders(r.status(), noBody ? -1 : r.body().length);
			if (!noBody) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(r.body());
				}
			}
			exchange.close();
		});
		server.start();
	}

	public String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	public void respond(String path, Response response) {
		responses.put(path, response);
	}

	public int hits(String path) {
		AtomicInteger n = hits.get(path);
		return (n == null) ? 0 : n.get();
	}

	Headers lastRequestHeaders() {
		return lastRequestHeaders;
	}

	@Override
	public void close() {
		server.stop(0);
	}

}
