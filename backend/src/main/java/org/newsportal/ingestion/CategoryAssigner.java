package org.newsportal.ingestion;

import java.sql.Array;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Maps publisher categories to app categories using category_mapping rules
 * (FR-027). Manual assignments are left alone; articles with no matching rule
 * stay uncategorized.
 */
@Component
public class CategoryAssigner {

	private final JdbcTemplate jdbc;

	public CategoryAssigner(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Must run inside the caller's transaction. */
	public void assign(Long articleId, Long sourceId, List<String> publisherCategories) {
		jdbc.update("DELETE FROM article_category WHERE article_id = ? AND assigned_by = 'PUBLISHER_MAPPING'",
				articleId);
		if (publisherCategories.isEmpty()) {
			return;
		}
		String[] labels = publisherCategories.stream()
			.map(c -> c.strip().toLowerCase(Locale.ROOT))
			.distinct()
			.toArray(String[]::new);
		jdbc.update(con -> {
			var ps = con.prepareStatement("""
					INSERT INTO article_category (article_id, category_id, assigned_by)
					SELECT DISTINCT ?, m.category_id, 'PUBLISHER_MAPPING'
					FROM category_mapping m
					WHERE (m.source_id IS NULL OR m.source_id = ?) AND m.publisher_category = ANY (?)
					ON CONFLICT (article_id, category_id) DO NOTHING
					""");
			Array array = con.createArrayOf("text", labels);
			ps.setLong(1, articleId);
			ps.setLong(2, sourceId);
			ps.setArray(3, array);
			return ps;
		});
	}

}
