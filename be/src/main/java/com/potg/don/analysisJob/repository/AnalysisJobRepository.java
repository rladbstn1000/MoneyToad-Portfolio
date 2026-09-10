package com.potg.don.analysisJob.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.potg.don.analysisJob.entity.AnalysisJob;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

	@Query("""
		SELECT j FROM AnalysisJob j
		WHERE (j.status = 'QUEUED' OR j.status = 'RUNNING')
		  AND (j.nextPollAt IS NULL OR j.nextPollAt <= :now)
		  AND (j.leasedUntil IS NULL OR j.leasedUntil < :now)
		ORDER BY j.createdAt ASC
		""")
	List<AnalysisJob> findPollableJobs(@Param("now") Instant now, Pageable pageable);
}
