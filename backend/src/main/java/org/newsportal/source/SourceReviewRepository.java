package org.newsportal.source;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceReviewRepository extends JpaRepository<SourceReview, Long> {

	List<SourceReview> findBySourceIdOrderByIdAsc(Long sourceId);

	List<SourceReview> findTop100BySourceIdOrderByIdDesc(Long sourceId);

}
