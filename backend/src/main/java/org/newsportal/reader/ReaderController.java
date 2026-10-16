package org.newsportal.reader;

import java.time.LocalDate;
import java.util.List;

import org.newsportal.reader.ReaderDtos.ArticleDetail;
import org.newsportal.reader.ReaderDtos.ArticlePage;
import org.newsportal.reader.ReaderDtos.Category;
import org.newsportal.reader.ReaderDtos.Publisher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, guest-accessible reader API (FR-037). */
@RestController
@RequestMapping("/api/v1")
public class ReaderController {

	private final ReaderService reader;

	public ReaderController(ReaderService reader) {
		this.reader = reader;
	}

	/**
	 * Latest feed and search. Multi-value filters accept comma-separated lists,
	 * e.g. {@code lang=en,hi}.
	 */
	@GetMapping("/articles")
	public ArticlePage articles(@RequestParam(name = "lang", required = false) List<String> languages,
			@RequestParam(name = "category", required = false) List<String> categories,
			@RequestParam(name = "source", required = false) List<String> sources,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(name = "q", required = false) String text,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "0") int size) {
		return reader.find(new ArticleQuery(languages, List.of(), categories, sources, from, to, text,
				Cursor.decode(cursor), size));
	}

	@GetMapping("/articles/{id}")
	public ResponseEntity<ArticleDetail> article(@PathVariable long id) {
		return ResponseEntity.of(reader.detail(id));
	}

	@GetMapping("/publishers")
	public List<Publisher> publishers() {
		return reader.publishers();
	}

	@GetMapping("/categories")
	public List<Category> categories() {
		return reader.categories();
	}

}
