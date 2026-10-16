package org.newsportal.reader;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.newsportal.article.ArticleContent;
import org.newsportal.article.ArticleContentRepository;
import org.newsportal.reader.ReaderDtos.ArticleDetail;
import org.newsportal.reader.ReaderDtos.ArticlePage;
import org.newsportal.reader.ReaderDtos.ArticleSummary;
import org.newsportal.reader.ReaderDtos.Category;
import org.newsportal.reader.ReaderDtos.FullTextStatus;
import org.newsportal.reader.ReaderDtos.Publisher;
import org.newsportal.reader.ReaderRepository.DetailRow;
import org.newsportal.reader.ReaderRepository.Row;
import org.newsportal.source.ContentUse;
import org.newsportal.source.SourceRightsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReaderService {

	private final ReaderRepository repository;

	private final ArticleContentRepository contents;

	private final SourceRightsService rights;

	private final Clock clock;

	public ReaderService(ReaderRepository repository, ArticleContentRepository contents, SourceRightsService rights,
			Clock clock) {
		this.repository = repository;
		this.contents = contents;
		this.rights = rights;
		this.clock = clock;
	}

	public ArticlePage find(ArticleQuery query) {
		List<Row> rows = repository.find(query, clock.instant(), query.size() + 1);
		boolean more = rows.size() > query.size();
		List<Row> page = more ? rows.subList(0, query.size()) : rows;
		String next = null;
		if (more) {
			Row last = page.getLast();
			next = new Cursor(last.sortAt(), last.summary().id()).encode();
		}
		List<ArticleSummary> items = page.stream().map(Row::summary).toList();
		return new ArticlePage(items, next);
	}

	/** Empty when the article does not exist or is not visible; callers answer 404 either way. */
	public Optional<ArticleDetail> detail(long id) {
		return repository.findDetail(id, clock.instant()).map(this::withFullText);
	}

	private ArticleDetail withFullText(DetailRow row) {
		ArticleDetail d = row.detail();
		// FR-018: full text only when display is permitted right now.
		if (!rights.check(row.sourceId(), ContentUse.FULL_TEXT_DISPLAY).allowed()) {
			return d;
		}
		Optional<String> body = contents.findById(d.id()).map(ArticleContent::getBodyHtml);
		FullTextStatus status = body.isPresent() ? FullTextStatus.AVAILABLE : FullTextStatus.NOT_PROVIDED;
		// FR-059: reading the full text aloud needs its own permission.
		boolean narrate = body.isPresent() && rights.check(row.sourceId(), ContentUse.NARRATION).allowed();
		return new ArticleDetail(d.id(), d.title(), d.excerpt(), d.author(), d.publisher(), d.sourceUrl(),
				d.language(), d.direction(), d.publishedAt(), d.publisherUpdatedAt(), d.ingestedAt(), d.lastSeenAt(),
				d.categories(), d.publisherCategories(), status, body.orElse(null), narrate);
	}

	public List<Publisher> publishers() {
		return repository.findVisiblePublishers(clock.instant());
	}

	public List<Category> categories() {
		return repository.findActiveCategoryCodes().stream().map(Category::new).toList();
	}

}
