package com.gct.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AnalysisHistoryService {
    private static final int MAX_RUNS = 20;

    private final AnalysisHistoryRepository repository;
    private final ObjectMapper objectMapper;

    public AnalysisHistoryService(AnalysisHistoryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> save(Map<String, Object> dashboard) {
        try {
            Map<String, Object> overall = map(dashboard.get("overall"));
            List<Map<String, Object>> suites = maps(dashboard.get("suites"));
            List<Map<String, Object>> incomplete = maps(dashboard.get("incompleteModules"));

            int failed = number(overall.get("failed"));
            boolean mismatch = Boolean.TRUE.equals(overall.get("fingerprintMismatch"));
            String status = mismatch ? "BUILD MISMATCH"
                    : (failed == 0 && incomplete.isEmpty() ? "READY FOR REVIEW" : "ATTENTION REQUIRED");

            AnalysisHistoryEntity entity = new AnalysisHistoryEntity();
            entity.setRunId(createRunId());
            entity.setAnalyzedAt(parseInstant(dashboard.get("generatedAt")));
            entity.setStatus(status);
            entity.setBuildFingerprint(text(dashboard.get("buildFingerprint")));
            entity.setAndroidVersion(text(dashboard.get("androidVersion")));
            entity.setSecurityPatch(text(dashboard.get("securityPatch")));
            entity.setSuiteCount(suites.size());
            entity.setTotalTests(number(overall.get("totalTests")));
            entity.setPassed(number(overall.get("passed")));
            entity.setFailed(failed);
            entity.setDashboardJson(objectMapper.writeValueAsString(dashboard));

            repository.save(entity);

            // Keep exactly the newest 20 successful analysis records.
            List<AnalysisHistoryEntity> all = repository.findAll(
                    org.springframework.data.domain.Sort.by(
                            org.springframework.data.domain.Sort.Direction.DESC, "analyzedAt"));
            if (all.size() > MAX_RUNS) {
                repository.deleteAll(all.subList(MAX_RUNS, all.size()));
            }

            return dashboard;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to save analysis history.", e);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> summaries() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AnalysisHistoryEntity entity : repository.findTop20ByOrderByAnalyzedAtDesc()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("runId", entity.getRunId());
            summary.put("analyzedAt", entity.getAnalyzedAt().toString());
            summary.put("status", entity.getStatus());
            summary.put("buildFingerprint", entity.getBuildFingerprint());
            summary.put("androidVersion", entity.getAndroidVersion());
            summary.put("securityPatch", entity.getSecurityPatch());
            summary.put("suiteCount", entity.getSuiteCount());
            summary.put("totalTests", entity.getTotalTests());
            summary.put("passed", entity.getPassed());
            summary.put("failed", entity.getFailed());
            summary.put("runLimit", MAX_RUNS);
            result.add(summary);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> find(String runId) {
        return repository.findByRunId(runId).flatMap(entity -> {
            try {
                return Optional.of(objectMapper.readValue(
                        entity.getDashboardJson(),
                        new TypeReference<LinkedHashMap<String, Object>>() {}));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    public int maxRuns() {
        return MAX_RUNS;
    }

    private static Instant parseInstant(Object value) {
        try {
            return Instant.parse(text(value));
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static String createRunId() {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return timestamp + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> source) {
            return (Map<String, Object>) source;
        }
        return Map.of();
    }

    private static int number(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
