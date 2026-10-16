package org.newsportal.source;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRightsRepository extends JpaRepository<SourceRights, Long> {

	Optional<SourceRights> findBySourceIdAndContentUse(Long sourceId, ContentUse contentUse);

	List<SourceRights> findBySourceId(Long sourceId);

}
