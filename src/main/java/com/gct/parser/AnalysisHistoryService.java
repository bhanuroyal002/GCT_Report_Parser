package com.gct.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

@Service
public class AnalysisHistoryService {
    private static final int MAX_RUNS = 20;
    private final ObjectMapper objectMapper;
    private final Path historyFile = Path.of("data", "analysis-history.json");

    public AnalysisHistoryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized Map<String, Object> save(Map<String, Object> dashboard) {
        List<Map<String, Object>> runs = readRuns();

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", createRunId());
        run.put("analyzedAt", dashboard.getOrDefault("generatedAt", Instant.now().toString()));
        run.put("dashboard", new LinkedHashMap<>(dashboard));

        runs.add(0, run);
        while (runs.size() > MAX_RUNS) {
            runs.remove(runs.size() - 1);
        }

        writeRuns(runs);
        return run;
    }

    public synchronized List<Map<String, Object>> summaries() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> run : readRuns()) {
            result.add(toSummary(run));
        }
        return result;
    }

    public synchronized Optional<Map<String, Object>> find(String runId) {
        return readRuns().stream()
                .filter(run -> runId.equals(String.valueOf(run.get("runId"))))
                .map(run -> (Map<String, Object>) run.get("dashboard"))
                .filter(Objects::nonNull)
                .findFirst();
    }

    public int maxRuns() {
        return MAX_RUNS;
    }

    private List<Map<String, Object>> readRuns() {
        if (!Files.exists(historyFile)) {
            return new ArrayList<>();
        }

        try {
            String json = Files.readString(historyFile);
            if (json.isBlank()) {
                return new ArrayList<>();
            }

            Map<String, Object> root = objectMapper.readValue(
                    json, new TypeReference<Map<String, Object>>() {});
            Object value = root.get("runs");

            if (!(value instanceof List<?> list)) {
                return new ArrayList<>();
            }

            List<Map<String, Object>> runs = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    map.forEach((key, val) -> normalized.put(String.valueOf(key), val));
                    runs.add(normalized);
                }
            }
            return runs;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void writeRuns(List<Map<String, Object>> runs) {
        try {
            Files.createDirectories(historyFile.getParent());

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("runs", runs);

            Path tempFile = historyFile.resolveSibling("analysis-history.json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), root);
            Files.move(tempFile, historyFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            try {
                Map<String, Object> root = new LinkedHashMap<>();
                root.put("runs", runs);
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(historyFile.toFile(), root);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to save analysis history.", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toSummary(Map<String, Object> run) {
        Map<String, Object> dashboard = run.get("dashboard") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        Map<String, Object> overall = dashboard.get("overall") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        List<Map<String, Object>> suites = dashboard.get("suites") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();

        List<Map<String, Object>> incomplete = dashboard.get("incompleteModules") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();

        int failed = number(overall.get("failed"));
        boolean mismatch = Boolean.TRUE.equals(overall.get("fingerprintMismatch"));
        String status = mismatch ? "BUILD MISMATCH"
                : (failed == 0 && incomplete.isEmpty() ? "READY FOR REVIEW" : "ATTENTION REQUIRED");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", run.get("runId"));
        summary.put("analyzedAt", run.get("analyzedAt"));
        summary.put("status", status);
        summary.put("buildFingerprint", dashboard.getOrDefault("buildFingerprint", "Not detected"));
        summary.put("androidVersion", dashboard.getOrDefault("androidVersion", "Not detected"));
        summary.put("securityPatch", dashboard.getOrDefault("securityPatch", "Not detected"));
        summary.put("suiteCount", suites.size());
        summary.put("totalTests", number(overall.get("totalTests")));
        summary.put("passed", number(overall.get("passed")));
        summary.put("failed", failed);
        summary.put("runLimit", MAX_RUNS);
        return summary;
    }

    private static int number(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String createRunId() {
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return timestamp + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
}
