package com.gct.parser;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistoryEntity, Long> {
    List<AnalysisHistoryEntity> findTop20ByOrderByAnalyzedAtDesc();
    Optional<AnalysisHistoryEntity> findByRunId(String runId);
}
