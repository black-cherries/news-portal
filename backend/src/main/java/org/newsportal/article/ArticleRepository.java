package org.newsportal.article;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleRepository extends JpaRepository<Article, Long> {

	Optional<Article> findBySourceIdAndPublisherGuid(Long sourceId, String publisherGuid);

	Optional<Article> findBySourceIdAndCanonicalUrl(Long sourceId, String canonicalUrl);

	List<Article> findBySourceId(Long sourceId);

}
