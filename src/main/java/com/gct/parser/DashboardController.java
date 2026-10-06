package com.gct.parser;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private static final List<String> SUPPORTED = List.of("CTS", "GTS", "TVTS", "STS", "VTS", "CTS-on-GSI", "CTS-Verifier");

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> analyze(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Upload at least one report ZIP file."));
        }

        List<Map<String, Object>> suites = new ArrayList<>();
        List<Map<String, Object>> incomplete = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();

        String fingerprint = "Not detected";
        String patch = "Not detected";

        // Reports are merged only when BOTH suite and build fingerprint match.
        // This prevents results from different device builds from being combined.
        Map<String, List<ParsedReport>> reportsByBuild = new LinkedHashMap<>();
        Set<String> fingerprints = new LinkedHashSet<>();

        for (MultipartFile file : files) {
            ParsedReport parsed = parse(file);
            if (parsed.suite == null) {
                continue;
            }

            String reportFingerprint = firstNonBlank(parsed.fingerprint, "Not detected");
            fingerprints.add(reportFingerprint);

            String suiteKey = parsed.suite.toLowerCase(Locale.ROOT);
            String fingerprintKey = reportFingerprint.toLowerCase(Locale.ROOT);
            String groupKey = suiteKey + "||" + fingerprintKey;

            reportsByBuild.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(parsed);

            if (!"Not detected".equals(parsed.fingerprint)) {
                fingerprint = parsed.fingerprint;
            }
            if (!"Not detected".equals(parsed.patch)) {
                patch = parsed.patch;
            }
        }

        for (List<ParsedReport> reports : reportsByBuild.values()) {
            ParsedReport merged = mergeReports(reports);
            suites.add(merged.toMap());
            incomplete.addAll(merged.incompleteModules);
            failures.addAll(merged.failures);
        }

        boolean fingerprintMismatch = fingerprints.size() > 1
                && !fingerprints.contains("Not detected");

        if (fingerprintMismatch) {
            fingerprint = "MULTIPLE BUILDS DETECTED";
        }

        int total = suites.stream().mapToInt(s -> number(s.get("testCases"))).sum();
        int passed = suites.stream().mapToInt(s -> number(s.get("passed"))).sum();
        int failed = suites.stream().mapToInt(s -> number(s.get("failed"))).sum();
        int assumptionFailures = suites.stream().mapToInt(s -> number(s.get("assumptionFailures"))).sum();
        int ignored = suites.stream().mapToInt(s -> number(s.get("ignored"))).sum();
        int warnings = suites.stream().mapToInt(s -> number(s.get("warnings"))).sum();

        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("totalTests", total);
        overall.put("passed", passed);
        overall.put("failed", failed);
        overall.put("assumptionFailures", assumptionFailures);
        overall.put("ignored", ignored);
        overall.put("warnings", warnings);
        overall.put("blocked", incomplete.size());
        overall.put("fingerprintMismatch", fingerprintMismatch);
        overall.put("multipleBuilds", fingerprints.size() > 1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("buildFingerprint", fingerprint);
        out.put("securityPatch", patch);

        // Group all suites by unique build fingerprint. If CTS/GTS/VTS share
        // the same fingerprint, they are represented as one build entry.
        Map<String, Map<String, Object>> buildMap = new LinkedHashMap<>();
        for (Map<String, Object> suite : suites) {
            String fp = firstNonBlank(textValue(suite.get("fingerprint")), "Not detected");
            String key = fp.toLowerCase(Locale.ROOT);

            Map<String, Object> build = buildMap.computeIfAbsent(key, k -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fingerprint", fp);
                item.put("securityPatch", "Not detected");
                item.put("androidVersion", "Not detected");
                item.put("suites", new LinkedHashSet<String>());
                return item;
            });

            String suitePatch = textValue(suite.get("securityPatch"));
            String suiteRelease = textValue(suite.get("release"));
            if (!suitePatch.isBlank() && !"Not detected".equalsIgnoreCase(suitePatch)) {
                build.put("securityPatch", suitePatch);
            }
            if (!suiteRelease.isBlank() && !"Not detected".equalsIgnoreCase(suiteRelease)) {
                build.put("androidVersion", suiteRelease);
            }

            @SuppressWarnings("unchecked")
            Set<String> buildSuites = (Set<String>) build.get("suites");
            buildSuites.add(textValue(suite.get("name")));
        }

        for (Map<String, Object> build : buildMap.values()) {
            @SuppressWarnings("unchecked")
            Set<String> buildSuites = (Set<String>) build.get("suites");
            build.put("suites", new ArrayList<>(buildSuites));
        }

        List<Map<String, Object>> builds = new ArrayList<>(buildMap.values());
        out.put("builds", builds);

        String androidVersion = builds.isEmpty()
                ? "Not detected"
                : textValue(builds.get(0).get("androidVersion"));
        out.put("androidVersion", androidVersion);
        out.put("fingerprints", new ArrayList<>(fingerprints));
        out.put("overall", overall);
        out.put("suites", suites);
        out.put("incompleteModules", incomplete);
        out.put("failures", failures);

        return ResponseEntity.ok(out);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return Map.of(
                "generatedAt", Instant.now().toString(),
                "buildFingerprint", "Not detected",
                "securityPatch", "Not detected",
                "overall", Map.of("totalTests", 0, "passed", 0, "failed", 0, "warnings", 0, "blocked", 0),
                "suites", List.of(),
                "incompleteModules", List.of(),
                "failures", List.of()
        );
    }

    @PostMapping(value = "/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> publish(@RequestBody Map<String, Object> dashboard) {
        String html = HtmlReportBuilder.build(dashboard);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Disposition", "attachment; filename=gct-certification-dashboard.html")
                .body(html.getBytes(StandardCharsets.UTF_8));
    }

    private ParsedReport parse(MultipartFile file) {
        List<ReportData> candidates = new ArrayList<>();
        Set<String> seenXml = new HashSet<>();

        try {
            collectReportsFromZip(file.getBytes(), candidates, seenXml, 0);
        } catch (Exception ignored) {
            // Keep any reports already collected from valid ZIP entries.
        }

        if (candidates.isEmpty()) {
            return ParsedReport.unknown();
        }

        List<ParsedReport> parsedReports = new ArrayList<>();
        for (ReportData candidate : candidates) {
            parsedReports.add(candidate.toParsedReport());
        }
        return mergeReports(parsedReports);
    }

    /**
     * Recursively scans ZIP files because Tradefed result archives can contain
     * additional result ZIPs inside the top-level suite ZIP.
     */
    private void collectReportsFromZip(byte[] zipBytes,
                                       List<ReportData> candidates, Set<String> seenXml,
                                       int depth) throws Exception {
        if (depth > 4) {
            return;
        }

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName().toLowerCase(Locale.ROOT);
                byte[] entryBytes = zis.readAllBytes();

                if (name.endsWith("test_result.xml")) {
                    String hash = Base64.getEncoder().encodeToString(
                            java.security.MessageDigest.getInstance("SHA-256").digest(entryBytes));
                    if (!seenXml.add(hash)) {
                        continue;
                    }

                    try (InputStream xmlInput = new ByteArrayInputStream(entryBytes)) {
                        ReportData candidate = parseTradefedResult(xmlInput, filenameSuite);
                        if (candidate != null) {
                            candidates.add(candidate);
                        }
                    } catch (Exception ignoredEntry) {
                        // Ignore only this result entry and continue scanning.
                    }
                } else if (name.endsWith(".zip")) {
                    try {
                        collectReportsFromZip(entryBytes, candidates, seenXml, depth + 1);
                    } catch (Exception ignoredNestedZip) {
                        // Ignore unrelated/corrupt nested ZIPs.
                    }
                }
            }
        }
    }

    private ReportData parseTradefedResult(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        // Result files can contain a DOCTYPE. Do not allow external entities or external DTDs.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder().parse(input);
        Element result = document.getDocumentElement();

        if (!"Result".equals(result.getTagName())) {
            NodeList results = document.getElementsByTagName("Result");
            if (results.getLength() == 0) {
                return null;
            }
            result = (Element) results.item(0);
        }

        String xmlSuite = attr(result, "suite_name");
        String suite = normalizeSuiteName(xmlSuite);

        if (suite == null) {
            return null;
        }

        Element summary = directChild(result, "Summary");
        Element build = directChild(result, "Build");

        int passed = intAttr(summary, "pass", 0);
        int failed = intAttr(summary, "failed", 0);
        int assumptionFailures = intAttr(summary, "assumption_failure",
                intAttr(summary, "assumption_failures", 0));
        int ignored = intAttr(summary, "ignored", 0);
        int warnings = intAttr(summary, "warning",
                intAttr(summary, "warnings", 0));

        int modulesDone = intAttr(summary, "modules_done", 0);
        int modulesTotal = intAttr(summary, "modules_total", 0);

        String suitePlan = attr(result, "suite_plan");
        String suiteVersion = attr(result, "suite_version");
        String suiteBuild = attr(result, "suite_build_number");
        String hostName = attr(result, "host_name");
        String osName = attr(result, "os_name");
        String osVersion = attr(result, "os_version");
        String start = firstNonBlank(attr(result, "start_display"), attr(result, "start"));
        String end = firstNonBlank(attr(result, "end_display"), attr(result, "end"));

        String fingerprint = attr(build, "build_fingerprint");
        String securityPatch = attr(build, "build_version_security_patch");
        String release = attr(build, "build_version_release");
        String sdk = attr(build, "build_version_sdk");
        String abis = attr(build, "build_abis");

        List<Map<String, Object>> modules = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        List<Map<String, Object>> incomplete = new ArrayList<>();
        List<TestResultData> testResults = new ArrayList<>();
        Map<String, Boolean> moduleDoneStates = new LinkedHashMap<>();

        NodeList moduleNodes = result.getElementsByTagName("Module");
        int modulePassTotal = 0;
        int moduleFailTotal = 0;
        int moduleAssumptionTotal = 0;
        int moduleIgnoredTotal = 0;

        for (int i = 0; i < moduleNodes.getLength(); i++) {
            Element module = (Element) moduleNodes.item(i);
            String moduleName = firstNonBlank(attr(module, "name"), "Unknown Module");
            String abi = attr(module, "abi");
            String displayName = abi == null || abi.isBlank()
                    ? moduleName
                    : abi + " " + moduleName;

            int modulePassed = intAttr(module, "pass", -1);
            int moduleFailed = 0;
            int moduleAssumptionFailures = 0;
            int moduleIgnored = 0;
            int moduleTests = 0;

            NodeList caseNodes = module.getElementsByTagName("TestCase");
            for (int c = 0; c < caseNodes.getLength(); c++) {
                Element testCase = (Element) caseNodes.item(c);
                String className = attr(testCase, "name");

                NodeList testNodes = testCase.getElementsByTagName("Test");
                for (int t = 0; t < testNodes.getLength(); t++) {
                    Element test = (Element) testNodes.item(t);
                    String resultValue = attr(test, "result");
                    String testName = firstNonBlank(attr(test, "name"), "Unknown Test");
                    String testKey = (firstNonBlank(abi, "") + "|" + moduleName + "|" +
                            firstNonBlank(className, "") + "|" + testName).toLowerCase(Locale.ROOT);
                    String failureMessage = "";
                    moduleTests++;

                    testResults.add(new TestResultData(testKey, suite, moduleName,
                            firstNonBlank(abi, ""), firstNonBlank(className, ""), testName,
                            resultValue, failureMessage));

                    if ("pass".equalsIgnoreCase(resultValue)) {
                        modulePassTotal++;
                    } else if ("fail".equalsIgnoreCase(resultValue)) {
                        moduleFailed++;
                        moduleFailTotal++;

                        Element failure = directChild(test, "Failure");
                        String message = failure == null ? "" : firstNonBlank(
                                attr(failure, "message"),
                                textOfDirectChild(failure, "StackTrace")
                        );
                        testResults.get(testResults.size() - 1).details = firstNonBlank(message, "Test failed");

                        failures.add(Map.of(
                                "suite", suite,
                                "module", displayName,
                                "testCase", firstNonBlank(className, "") + "#"
                                        + firstNonBlank(attr(test, "name"), "Unknown Test"),
                                "details", firstNonBlank(message, "Test failed")
                        ));
                    } else if ("assumption_failure".equalsIgnoreCase(resultValue)
                            || "assumption-failure".equalsIgnoreCase(resultValue)) {
                        moduleAssumptionFailures++;
                        moduleAssumptionTotal++;
                    } else if ("ignored".equalsIgnoreCase(resultValue)) {
                        moduleIgnored++;
                        moduleIgnoredTotal++;
                    }
                }
            }

            if (modulePassTotal < 0 && modulePassed < 0) {
                modulePassed = Math.max(0, moduleTests - moduleFailed);
            } else if (modulePassed < 0) {
                modulePassed = Math.max(0, moduleTests - moduleFailed);
            }

            boolean done = Boolean.parseBoolean(attr(module, "done"));
            String moduleKey = (firstNonBlank(abi, "") + "|" + moduleName).toLowerCase(Locale.ROOT);
            moduleDoneStates.put(moduleKey, done);
            if (!done) {
                incomplete.add(Map.of(
                        "suite", suite,
                        "module", displayName,
                        "reason", "Module is marked done=false in Tradefed result"
                ));
            }

            Map<String, Object> moduleMap = new LinkedHashMap<>();
            moduleMap.put("name", moduleName);
            moduleMap.put("abi", firstNonBlank(abi, ""));
            moduleMap.put("passed", modulePassed);
            moduleMap.put("failed", moduleFailed);
            moduleMap.put("assumptionFailures", moduleAssumptionFailures);
            moduleMap.put("ignored", moduleIgnored);
            moduleMap.put("totalTests", moduleTests);
            moduleMap.put("done", done);
            modules.add(moduleMap);
        }

        // Some result versions omit summary module counts. Fall back to actual Module elements.
        // Likewise derive assumption-failure/ignored counts from individual tests when
        // those summary attributes are absent or incomplete.
        if (assumptionFailures == 0 && moduleAssumptionTotal > 0) {
            assumptionFailures = moduleAssumptionTotal;
        }
        if (ignored == 0 && moduleIgnoredTotal > 0) {
            ignored = moduleIgnoredTotal;
        }
        if (modulesTotal == 0) {
            modulesTotal = modules.size();
        }
        if (modulesDone == 0 && !modules.isEmpty()) {
            modulesDone = (int) modules.stream().filter(m -> Boolean.TRUE.equals(m.get("done"))).count();
        }

        // Summary counts are authoritative. If an older report has no summary pass/fail,
        // derive them from the module/test records.
        if (passed == 0 && failed == 0 && !modules.isEmpty()) {
            passed = modulePassTotal;
            failed = moduleFailTotal;
        }

        int testCases = passed + failed + assumptionFailures + ignored;
        String status = modulesDone >= modulesTotal && modulesTotal > 0 ? "COMPLETED" : "INCOMPLETE";

        ReportData data = new ReportData();
        data.suite = suite;
        data.plan = suitePlan;
        data.version = suiteVersion;
        data.buildNumber = suiteBuild;
        data.hostInfo = buildHostInfo(hostName, osName, osVersion);
        data.start = start;
        data.end = end;
        data.passed = passed;
        data.failed = failed;
        data.assumptionFailures = assumptionFailures;
        data.ignored = ignored;
        data.warnings = warnings;
        data.modulesDone = modulesDone;
        data.modulesTotal = modulesTotal;
        data.fingerprint = firstNonBlank(fingerprint, "Not detected");
        data.patch = firstNonBlank(securityPatch, "Not detected");
        data.release = release;
        data.sdk = sdk;
        data.abis = abis;
        data.testCases = testCases;
        data.status = status;
        data.moduleDetails = modules;
        data.failures = failures;
        data.incomplete = incomplete;
        data.testResults = testResults;
        data.moduleDoneStates = moduleDoneStates;
        return data;
    }

    /**
     * Merge multiple result ZIPs belonging to the same suite.
     *
     * Modules are treated as the unit of uniqueness. If the same module appears
     * in more than one ZIP, keep the richer record instead of counting it twice.
     * This is important when users upload split/partial result archives.
     */
    /**
     * Merge reports using test-case identity, not aggregate counters.
     *
     * If a test fails in an earlier execution and the same test passes in a
     * later rerun, the later PASS replaces the earlier FAIL. This prevents
     * rerun results (for example TVTS YouTubeTS) from being double-counted.
     */
    private static ParsedReport mergeReports(List<ParsedReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return ParsedReport.unknown();
        }
        if (reports.size() == 1) {
            return reports.get(0);
        }

        String suite = reports.get(0).suite;
        String plan = null, version = null, buildNumber = null, hostInfo = null;
        String start = null, end = null, fingerprint = null, patch = null;
        String release = null, sdk = null, abis = null;

        Map<String, TestResultData> testResults = new LinkedHashMap<>();
        Map<String, Boolean> moduleDoneStates = new LinkedHashMap<>();
        Map<String, Map<String, Object>> moduleInfo = new LinkedHashMap<>();

        for (ParsedReport report : reports) {
            plan = firstNonBlank(plan, report.plan);
            version = firstNonBlank(version, report.version);
            buildNumber = firstNonBlank(buildNumber, report.buildNumber);
            hostInfo = firstNonBlank(hostInfo, report.hostInfo);
            start = firstNonBlank(start, report.start);
            end = firstNonBlank(end, report.end);
            fingerprint = firstNonBlank(fingerprint,
                    "Not detected".equals(report.fingerprint) ? null : report.fingerprint);
            patch = firstNonBlank(patch,
                    "Not detected".equals(report.patch) ? null : report.patch);
            release = firstNonBlank(release, report.release);
            sdk = firstNonBlank(sdk, report.sdk);
            abis = firstNonBlank(abis, report.abis);

            moduleDoneStates.putAll(report.moduleDoneStates);

            for (TestResultData test : report.testResults) {
                // Later report wins. This is the rerun reconciliation rule.
                testResults.put(test.key, test);
            }

            for (Map<String, Object> module : report.moduleDetails) {
                String key = (textValue(module.get("abi")) + "|" + textValue(module.get("name")))
                        .toLowerCase(Locale.ROOT);
                Map<String, Object> existing = moduleInfo.get(key);
                if (existing == null || isRicherModule(module, existing)) {
                    moduleInfo.put(key, new LinkedHashMap<>(module));
                }
            }
        }

        // Rebuild all test-result counters from the deduplicated final test state.
        int passed = 0, failed = 0, assumptionFailures = 0, ignored = 0;
        Map<String, Integer> modulePassed = new HashMap<>();
        Map<String, Integer> moduleFailed = new HashMap<>();
        Map<String, Integer> moduleAssumption = new HashMap<>();
        Map<String, Integer> moduleIgnored = new HashMap<>();
        Map<String, Integer> moduleTotal = new HashMap<>();

        List<Map<String, Object>> failures = new ArrayList<>();

        for (TestResultData test : testResults.values()) {
            String moduleKey = (test.abi + "|" + test.module).toLowerCase(Locale.ROOT);
            moduleTotal.merge(moduleKey, 1, Integer::sum);

            if ("pass".equalsIgnoreCase(test.result)) {
                passed++;
                modulePassed.merge(moduleKey, 1, Integer::sum);
            } else if ("fail".equalsIgnoreCase(test.result)) {
                failed++;
                moduleFailed.merge(moduleKey, 1, Integer::sum);

                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("suite", test.suite);
                failure.put("module", test.abi.isBlank() ? test.module : test.abi + " " + test.module);
                failure.put("testCase", test.testCase + "#" + test.name);
                failure.put("details", firstNonBlank(test.details, "Test failed"));
                failures.add(failure);
            } else if ("assumption_failure".equalsIgnoreCase(test.result)
                    || "assumption-failure".equalsIgnoreCase(test.result)) {
                assumptionFailures++;
                moduleAssumption.merge(moduleKey, 1, Integer::sum);
            } else if ("ignored".equalsIgnoreCase(test.result)) {
                ignored++;
                moduleIgnored.merge(moduleKey, 1, Integer::sum);
            }
        }

        // Build module rows from unique test cases and preserve module done state.
        Set<String> allModuleKeys = new LinkedHashSet<>(moduleInfo.keySet());
        allModuleKeys.addAll(moduleTotal.keySet());

        List<Map<String, Object>> modules = new ArrayList<>();
        List<Map<String, Object>> incomplete = new ArrayList<>();

        for (String key : allModuleKeys) {
            Map<String, Object> source = moduleInfo.getOrDefault(key, new LinkedHashMap<>());
            String name = textValue(source.get("name"));
            String abi = textValue(source.get("abi"));

            if (name.isBlank()) {
                int sep = key.indexOf('|');
                abi = sep >= 0 ? key.substring(0, sep) : "";
                name = sep >= 0 ? key.substring(sep + 1) : key;
            }

            boolean done = moduleDoneStates.getOrDefault(key,
                    Boolean.TRUE.equals(source.get("done")));

            Map<String, Object> module = new LinkedHashMap<>();
            module.put("name", name);
            module.put("abi", abi);
            module.put("passed", modulePassed.getOrDefault(key, 0));
            module.put("failed", moduleFailed.getOrDefault(key, 0));
            module.put("assumptionFailures", moduleAssumption.getOrDefault(key, 0));
            module.put("ignored", moduleIgnored.getOrDefault(key, 0));
            module.put("totalTests", moduleTotal.getOrDefault(key, 0));
            module.put("done", done);
            modules.add(module);

            if (!done) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("suite", suite);
                item.put("module", abi.isBlank() ? name : abi + " " + name);
                item.put("failed", moduleFailed.getOrDefault(key, 0));
                item.put("reason", "Module is marked done=false in Tradefed result");
                incomplete.add(item);
            }
        }

        int modulesTotal = modules.size();
        int modulesDone = (int) modules.stream()
                .filter(m -> Boolean.TRUE.equals(m.get("done")))
                .count();

        int warnings = reports.stream().mapToInt(r -> r.warnings).max().orElse(0);
        int testCases = passed + failed + assumptionFailures + ignored;

        // If a report contains no individual Test nodes, retain its summary counters.
        if (testResults.isEmpty()) {
            passed = reports.stream().mapToInt(r -> r.passed).sum();
            failed = reports.stream().mapToInt(r -> r.failed).sum();
            assumptionFailures = reports.stream().mapToInt(r -> r.assumptionFailures).sum();
            ignored = reports.stream().mapToInt(r -> r.ignored).sum();
            testCases = passed + failed + assumptionFailures + ignored;
        }

        String mergedFingerprint = firstNonBlank(fingerprint, "Not detected");
        String mergedPatch = firstNonBlank(patch, "Not detected");
        String status = modulesTotal > 0 && modulesDone >= modulesTotal
                ? "COMPLETED" : "INCOMPLETE";

        ParsedReport merged = new ParsedReport(
                suite, modulesTotal, modulesDone, testCases,
                passed, failed, assumptionFailures, ignored, warnings, status,
                mergedFingerprint, mergedPatch
        );

        merged.plan = plan;
        merged.version = version;
        merged.buildNumber = buildNumber;
        merged.hostInfo = hostInfo;
        merged.start = start;
        merged.end = end;
        merged.release = release;
        merged.sdk = sdk;
        merged.abis = abis;
        merged.moduleDetails = modules;
        merged.failures.addAll(failures);
        merged.incompleteModules.addAll(incomplete);
        merged.testResults.addAll(testResults.values());

        return merged;
    }

    private static boolean isRicherModule(Map<String, Object> candidate, Map<String, Object> current) {
        boolean candidateDone = Boolean.TRUE.equals(candidate.get("done"));
        boolean currentDone = Boolean.TRUE.equals(current.get("done"));

        if (candidateDone != currentDone) {
            return candidateDone;
        }

        int candidateTests = intValue(candidate.get("totalTests"));
        int currentTests = intValue(current.get("totalTests"));
        if (candidateTests != currentTests) {
            return candidateTests > currentTests;
        }

        int candidateFailed = intValue(candidate.get("failed"));
        int currentFailed = intValue(current.get("failed"));
        return candidateFailed > currentFailed;
    }

    private static String displayModuleName(Map<String, Object> module) {
        String abi = textValue(module.get("abi"));
        String name = textValue(module.get("name"));
        return abi.isBlank() ? name : abi + " " + name;
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String textValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String buildHostInfo(String host, String os, String version) {
        if (host == null && os == null && version == null) {
            return "Not detected";
        }
        return "Result/@start " + firstNonBlank(host, "unknown")
                + " (" + firstNonBlank(os, "unknown")
                + " - " + firstNonBlank(version, "unknown") + ")";
    }

    /**
     * The suite identity comes from Tradefed's Result/@suite_name.
     * Uploaded filenames are intentionally ignored so the parser works
     * with arbitrary report archive names.
     */
    private static String normalizeSuiteName(String suiteName) {
        if (suiteName == null || suiteName.isBlank()) {
            return null;
        }

        String normalized = suiteName.trim();
        if ("CTS_VERIFIER".equalsIgnoreCase(normalized)
                || "CTS-VERIFIER".equalsIgnoreCase(normalized)
                || "CTS VERIFIER".equalsIgnoreCase(normalized)) {
            return "CTS-Verifier";
        }
        return normalized;
    }

    private static Element directChild(Element parent, String tagName) {
        if (parent == null) {
            return null;
        }

        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String textOfDirectChild(Element parent, String tagName) {
        Element child = directChild(parent, tagName);
        return child == null ? null : child.getTextContent();
    }

    private static String attr(Element element, String name) {
        if (element == null || !element.hasAttribute(name)) {
            return null;
        }
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static int intAttr(Element element, String name, int fallback) {
        String value = attr(element, name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static final class ReportData {
        String suite;
        String plan;
        String version;
        String buildNumber;
        String hostInfo;
        String start;
        String end;
        int passed;
        int failed;
        int assumptionFailures;
        int ignored;
        int warnings;
        int modulesDone;
        int modulesTotal;
        int testCases;
        String fingerprint;
        String patch;
        String release;
        String sdk;
        String abis;
        String status;
        List<Map<String, Object>> moduleDetails = List.of();
        List<Map<String, Object>> failures = List.of();
        List<Map<String, Object>> incomplete = List.of();
        List<TestResultData> testResults = List.of();
        Map<String, Boolean> moduleDoneStates = Map.of();

        int score() {
            return modulesTotal * 1_000_000 + testCases;
        }

        ParsedReport toParsedReport() {
            ParsedReport parsed = new ParsedReport(
                    suite, modulesTotal, modulesDone, testCases,
                    passed, failed, assumptionFailures, ignored, warnings, status, fingerprint, patch
            );
            parsed.plan = plan;
            parsed.version = version;
            parsed.buildNumber = buildNumber;
            parsed.hostInfo = hostInfo;
            parsed.start = start;
            parsed.end = end;
            parsed.release = release;
            parsed.sdk = sdk;
            parsed.abis = abis;
            parsed.moduleDetails = moduleDetails;
            parsed.failures.addAll(failures);
            parsed.incompleteModules.addAll(incomplete);
            parsed.testResults.addAll(testResults);
            parsed.moduleDoneStates.putAll(moduleDoneStates);
            return parsed;
        }
    }

    private static final class TestResultData {
        final String key;
        final String suite;
        final String module;
        final String abi;
        final String testCase;
        final String name;
        final String result;
        String details;

        TestResultData(String key, String suite, String module, String abi,
                       String testCase, String name, String result, String details) {
            this.key = key;
            this.suite = suite;
            this.module = module;
            this.abi = abi;
            this.testCase = testCase;
            this.name = name;
            this.result = result;
            this.details = details;
        }
    }

    private static final class ParsedReport {
        final String suite;
        final int modules;
        final int completedModules;
        final int testCases;
        final int passed;
        final int failed;
        final int assumptionFailures;
        final int ignored;
        final int warnings;
        final String status;
        final String fingerprint;
        final String patch;

        String plan;
        String version;
        String buildNumber;
        String hostInfo;
        String start;
        String end;
        String release;
        String sdk;
        String abis;
        List<Map<String, Object>> moduleDetails = List.of();
        final List<TestResultData> testResults = new ArrayList<>();
        final Map<String, Boolean> moduleDoneStates = new LinkedHashMap<>();

        final List<Map<String, Object>> incompleteModules = new ArrayList<>();
        final List<Map<String, Object>> failures = new ArrayList<>();

        ParsedReport(String suite, int modules, int completedModules, int testCases,
                     int passed, int failed, int assumptionFailures, int ignored, int warnings,
                     String status, String fingerprint, String patch) {
            this.suite = suite;
            this.modules = modules;
            this.completedModules = completedModules;
            this.testCases = testCases;
            this.passed = passed;
            this.failed = failed;
            this.assumptionFailures = assumptionFailures;
            this.ignored = ignored;
            this.warnings = warnings;
            this.status = status;
            this.fingerprint = fingerprint;
            this.patch = patch;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", suite);
            map.put("plan", plan);
            map.put("version", version);
            map.put("buildNumber", buildNumber);
            map.put("hostInfo", hostInfo);
            map.put("start", start);
            map.put("end", end);
            map.put("passed", passed);
            map.put("failed", failed);
            map.put("assumptionFailures", assumptionFailures);
            map.put("ignored", ignored);
            map.put("warnings", warnings);
            map.put("modules", modules);
            map.put("completedModules", completedModules);
            map.put("testCases", testCases);
            map.put("status", status);
            map.put("fingerprint", fingerprint);
            map.put("securityPatch", patch);
            map.put("release", release);
            map.put("sdk", sdk);
            map.put("abis", abis);
            map.put("moduleDetails", moduleDetails);
            return map;
        }

        static ParsedReport unknown() {
            return new ParsedReport(null, 0, 0, 0, 0, 0, 0, 0, 0,
                    "UNSUPPORTED", "Not detected", "Not detected");
        }
    }
}
