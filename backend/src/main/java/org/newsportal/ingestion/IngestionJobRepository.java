package org.newsportal.ingestion;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, Long> {

	List<IngestionJob> findByFeedIdOrderByIdAsc(Long feedId);

	List<IngestionJob> findByFeedIdOrderByIdDesc(Long feedId, Limit limit);

	boolean existsByFeedIdAndOutcomeInAndStartedAtAfter(Long feedId, Collection<FeedOutcome> outcomes, Instant after);

}
