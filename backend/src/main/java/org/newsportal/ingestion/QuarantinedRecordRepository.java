package org.newsportal.ingestion;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuarantinedRecordRepository extends JpaRepository<QuarantinedRecord, Long> {

	List<QuarantinedRecord> findByJobId(Long jobId);

}
