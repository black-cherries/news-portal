package org.newsportal.ingestion;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SourceFeedRepository extends JpaRepository<SourceFeed, Long> {

	@Query("select f.id from SourceFeed f where f.enabled = true and f.nextRunAt <= :now order by f.nextRunAt")
	List<Long> findDueFeedIds(Instant now);

	List<SourceFeed> findBySourceIdOrderByIdAsc(Long sourceId);

}
