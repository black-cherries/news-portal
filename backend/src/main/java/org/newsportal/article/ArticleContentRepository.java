package org.newsportal.article;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleContentRepository extends JpaRepository<ArticleContent, Long> {
}
