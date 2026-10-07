package com.gct.parser;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "analysis_history", indexes = {
        @Index(name = "idx_analysis_history_analyzed_at", columnList = "analyzed_at")
})
public class AnalysisHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, unique = true, length = 80)
    private String runId;

    @Column(name = "analyzed_at", nullable = false)
    private Instant analyzedAt;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(name = "build_fingerprint", length = 1000)
    private String buildFingerprint;

    @Column(name = "android_version", length = 100)
    private String androidVersion;

    @Column(name = "security_patch", length = 100)
    private String securityPatch;

    @Column(name = "suite_count", nullable = false)
    private int suiteCount;

    @Column(name = "total_tests", nullable = false)
    private int totalTests;

    @Column(nullable = false)
    private int passed;

    @Column(nullable = false)
    private int failed;

    @Lob
    @Column(name = "dashboard_json", nullable = false, columnDefinition = "TEXT")
    private String dashboardJson;

    protected AnalysisHistoryEntity() {
    }

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Instant getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(Instant analyzedAt) { this.analyzedAt = analyzedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBuildFingerprint() { return buildFingerprint; }
    public void setBuildFingerprint(String buildFingerprint) { this.buildFingerprint = buildFingerprint; }
    public String getAndroidVersion() { return androidVersion; }
    public void setAndroidVersion(String androidVersion) { this.androidVersion = androidVersion; }
    public String getSecurityPatch() { return securityPatch; }
    public void setSecurityPatch(String securityPatch) { this.securityPatch = securityPatch; }
    public int getSuiteCount() { return suiteCount; }
    public void setSuiteCount(int suiteCount) { this.suiteCount = suiteCount; }
    public int getTotalTests() { return totalTests; }
    public void setTotalTests(int totalTests) { this.totalTests = totalTests; }
    public int getPassed() { return passed; }
    public void setPassed(int passed) { this.passed = passed; }
    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }
    public String getDashboardJson() { return dashboardJson; }
    public void setDashboardJson(String dashboardJson) { this.dashboardJson = dashboardJson; }
}
