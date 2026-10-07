package com.gct.parser;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
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

        List<ParsedReport> allReports = new ArrayList<>();
        List<Map<String, Object>> reportDiagnostics = new ArrayList<>();

        for (MultipartFile file : files) {
            ParseResult result = parse(file);
            allReports.addAll(result.reports);

            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("file", file.getOriginalFilename());
            diagnostic.put("xmlFilesFound", result.xmlFilesFound);
            diagnostic.put("recognizedReports", result.reports.size());
            diagnostic.put("errors", result.errors);
            reportDiagnostics.add(diagnostic);
        }

        // Build identity is determined ONLY by the normalized build fingerprint.
        // Suite is a child of the build, not part of build identity.
        Map<String, List<ParsedReport>> reportsByBuild = new LinkedHashMap<>();
        Set<String> knownFingerprints = new LinkedHashSet<>();
        boolean unknownFingerprintPresent = false;

        for (ParsedReport report : allReports) {
            String fingerprintValue = firstNonBlank(report.fingerprint, "Not detected").trim();
            String fingerprintKey = normalizeFingerprint(fingerprintValue);

            if (fingerprintKey.isBlank() || "not detected".equals(fingerprintKey)) {
                unknownFingerprintPresent = true;
            } else {
                knownFingerprints.add(fingerprintKey);
            }

            reportsByBuild.computeIfAbsent(fingerprintKey.isBlank() ? "not detected" : fingerprintKey,
                    k -> new ArrayList<>()).add(report);
        }

        // A known build + unknown build identity cannot safely be merged.
        // Treat it as incomplete build identity rather than silently choosing one build.
        boolean multipleKnownBuilds = knownFingerprints.size() > 1;
        boolean buildIdentityIncomplete = unknownFingerprintPresent && !knownFingerprints.isEmpty();
        boolean fingerprintMismatch = multipleKnownBuilds || buildIdentityIncomplete;

        List<Map<String, Object>> suites = new ArrayList<>();
        List<Map<String, Object>> incomplete = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();

        for (List<ParsedReport> buildReports : reportsByBuild.values()) {
            // parse() has already merged same-suite reports with the same fingerprint.
            // Keep that build grouping intact and expose one row per suite.
            for (ParsedReport report : buildReports) {
                suites.add(report.toMap());
                incomplete.addAll(report.incompleteModules);
                failures.addAll(report.failures);
            }
        }

        String fingerprint = "Not detected";
        String patch = "Not detected";

        if (knownFingerprints.size() == 1 && !buildIdentityIncomplete) {
            ParsedReport firstKnown = allReports.stream()
                    .filter(r -> !normalizeFingerprint(r.fingerprint).isBlank()
                            && !"not detected".equals(normalizeFingerprint(r.fingerprint)))
                    .findFirst()
                    .orElse(null);
            if (firstKnown != null) {
                fingerprint = firstKnown.fingerprint;
                patch = firstKnown.patch;
            }
        } else if (fingerprintMismatch) {
            fingerprint = multipleKnownBuilds
                    ? "MULTIPLE BUILDS DETECTED"
                    : "BUILD IDENTITY INCOMPLETE";
        }

        int total = suites.stream().mapToInt(s -> number(s.get("testCases"))).sum();
        int passed = suites.stream().mapToInt(s -> number(s.get("passed"))).sum();
        int failed = suites.stream().mapToInt(s -> number(s.get("failed"))).sum();
        int assumptionFailures = suites.stream().mapToInt(s -> number(s.get("assumptionFailures"))).sum();
        int ignored = suites.stream().mapToInt(s -> number(s.get("ignored"))).sum();
        int warnings = suites.stream().mapToInt(s -> number(s.get("warnings"))).sum();

        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("totalTests", fingerprintMismatch ? 0 : total);
        overall.put("passed", fingerprintMismatch ? 0 : passed);
        overall.put("failed", fingerprintMismatch ? 0 : failed);
        overall.put("assumptionFailures", fingerprintMismatch ? 0 : assumptionFailures);
        overall.put("ignored", fingerprintMismatch ? 0 : ignored);
        overall.put("warnings", fingerprintMismatch ? 0 : warnings);
        overall.put("blocked", fingerprintMismatch ? 0 : incomplete.size());
        overall.put("fingerprintMismatch", fingerprintMismatch);
        overall.put("multipleBuilds", multipleKnownBuilds);
        overall.put("buildIdentityIncomplete", buildIdentityIncomplete);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("buildFingerprint", fingerprint);
        out.put("securityPatch", patch);

        // One build entry per unique fingerprint. Suites sharing a fingerprint
        // are listed together under that build.
        Map<String, Map<String, Object>> buildMap = new LinkedHashMap<>();
        for (Map<String, Object> suite : suites) {
            String fp = firstNonBlank(textValue(suite.get("fingerprint")), "Not detected");
            String key = normalizeFingerprint(fp);
            if (key.isBlank()) key = "not detected";

            Map<String, Object> build = buildMap.computeIfAbsent(key, k -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fingerprint", fp);
                item.put("securityPatch", "Not detected");
                item.put("androidVersion", "Not detected");
                item.put("buildId", "Not detected");
                item.put("buildType", "Not detected");
                item.put("sdk", "Not detected");
                item.put("abis", "Not detected");
                item.put("suites", new LinkedHashSet<String>());
                return item;
            });

            String suitePatch = textValue(suite.get("securityPatch"));
            String suiteRelease = textValue(suite.get("release"));
            String suiteBuildId = textValue(suite.get("buildId"));
            String suiteBuildType = textValue(suite.get("buildType"));
            String suiteSdk = textValue(suite.get("sdk"));
            String suiteAbis = textValue(suite.get("abis"));

            if (!suitePatch.isBlank() && !"Not detected".equalsIgnoreCase(suitePatch)) build.put("securityPatch", suitePatch);
            if (!suiteRelease.isBlank() && !"Not detected".equalsIgnoreCase(suiteRelease)) build.put("androidVersion", suiteRelease);
            if (!suiteBuildId.isBlank() && !"Not detected".equalsIgnoreCase(suiteBuildId)) build.put("buildId", suiteBuildId);
            if (!suiteBuildType.isBlank() && !"Not detected".equalsIgnoreCase(suiteBuildType)) build.put("buildType", suiteBuildType);
            if (!suiteSdk.isBlank() && !"Not detected".equalsIgnoreCase(suiteSdk)) build.put("sdk", suiteSdk);
            if (!suiteAbis.isBlank() && !"Not detected".equalsIgnoreCase(suiteAbis)) build.put("abis", suiteAbis);

            @SuppressWarnings("unchecked")
            Set<String> buildSuites = (Set<String>) build.get("suites");
            String suiteName = textValue(suite.get("name"));
            if (!suiteName.isBlank()) buildSuites.add(suiteName);
        }

        for (Map<String, Object> build : buildMap.values()) {
            @SuppressWarnings("unchecked")
            Set<String> buildSuites = (Set<String>) build.get("suites");
            build.put("suites", new ArrayList<>(buildSuites));
        }

        List<Map<String, Object>> builds = new ArrayList<>(buildMap.values());
        out.put("builds", builds);
        out.put("androidVersion", builds.isEmpty()
                ? "Not detected"
                : textValue(builds.get(0).get("androidVersion")));
        out.put("fingerprints", new ArrayList<>(knownFingerprints));
        out.put("reportDiagnostics", reportDiagnostics);
        out.put("uploadedFiles", files.length);
        out.put("xmlReportsFound", reportDiagnostics.stream()
                .mapToInt(d -> number(d.get("xmlFilesFound"))).sum());
        out.put("recognizedReports", allReports.size());
        out.put("overall", overall);
        out.put("suites", suites);
        out.put("incompleteModules", fingerprintMismatch ? List.of() : incomplete);
        out.put("failures", fingerprintMismatch ? List.of() : failures);

        return ResponseEntity.ok(out);
    }

    private static String normalizeFingerprint(String fingerprint) {
        String value = firstNonBlank(fingerprint, "");
        return value.trim().toLowerCase(Locale.ROOT);
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

    /**
     * Extract every Tradefed result independently from the uploaded archive.
     *
     * The ZIP hierarchy is traversed level by level:
     * 1. Check every entry for test_result.xml.
     * 2. Keep every unique XML result that is found.
     * 3. If an entry is another ZIP, open it and repeat the same check.
     * 4. Continue until the deepest nested ZIP has been processed.
     *
     * Exact duplicate XML files are removed by SHA-256. Different XML reports
     * are kept independently so they can later be compared and merged using
     * testcase/module identity.
     */
    private List<ParsedReport> parse(MultipartFile file) {
        List<ReportData> candidates = new ArrayList<>();
        Set<String> seenXml = new HashSet<>();

        try {
            collectReportsFromZip(file.getBytes(), candidates, seenXml, 0);
        } catch (Exception ignored) {
            // Keep any reports already collected from valid ZIP entries.
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        // First convert each unique XML into an independent report.
        List<ParsedReport> parsedReports = new ArrayList<>();
        for (ReportData candidate : candidates) {
            ParsedReport report = candidate.toParsedReport();
            if (report.suite != null) {
                parsedReports.add(report);
            }
        }

        // Merge only reports that belong to the same logical suite/build group.
        // Different reports remain separate until this grouping decision is made.
        Map<String, List<ParsedReport>> groups = new LinkedHashMap<>();
        for (ParsedReport report : parsedReports) {
            String suiteKey = firstNonBlank(report.suite, "unknown")
                    .toLowerCase(Locale.ROOT);
            String fingerprintKey = firstNonBlank(report.fingerprint, "Not detected")
                    .toLowerCase(Locale.ROOT);
            String key = suiteKey + "||" + fingerprintKey;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(report);
        }

        List<ParsedReport> mergedReports = new ArrayList<>();
        for (List<ParsedReport> group : groups.values()) {
            mergedReports.add(mergeReports(group));
        }

        return mergedReports;
    }

    /**
     * Recursively scans every ZIP level.
     *
     * At each level we always perform both checks:
     * - test_result.xml -> parse and keep it
     * - nested .zip      -> open it and repeat the same process
     *
     * This supports ZIP -> ZIP -> ZIP -> test_result.xml and deeper structures.
     */
    private void collectReportsFromZip(byte[] zipBytes,
                                       List<ReportData> candidates, Set<String> seenXml,
                                       int depth) throws Exception {
        if (depth > 20) {
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

                // Check for a result XML at this ZIP level.
                if (name.endsWith("test_result.xml")) {
                    String hash = Base64.getEncoder().encodeToString(
                            java.security.MessageDigest.getInstance("SHA-256").digest(entryBytes));

                    // Exact duplicate XML: keep only the first copy.
                    if (!seenXml.add(hash)) {
                        continue;
                    }

                    try (InputStream xmlInput = new ByteArrayInputStream(entryBytes)) {
                        ReportData candidate = parseTradefedResult(xmlInput);
                        if (candidate != null) {
                            candidates.add(candidate);
                        }
                    } catch (Exception ignoredEntry) {
                        // Ignore only this invalid XML and continue scanning.
                    }
                }

                // Check for a nested ZIP at this same level as well.
                // Do not use else-if: a ZIP level must always continue scanning
                // all of its entries, including both XMLs and nested archives.
                if (name.endsWith(".zip")) {
                    try {
                        collectReportsFromZip(entryBytes, candidates, seenXml, depth + 1);
                    } catch (Exception ignoredNestedZip) {
                        // Ignore only this invalid/unreadable nested ZIP.
                    }
                }
            }
        }
    }

    /**
     * Parse a Tradefed result using StAX instead of DOM.
     *
     * Tradefed test_result.xml files can contain hundreds of thousands of
     * Test elements. DOM builds an in-memory object for the entire XML tree,
     * which can exhaust the JVM heap even when the ZIP itself is manageable.
     * StAX reads the XML as a forward-only stream and keeps only the small
     * amount of state needed for the dashboard.
     */
    private ReportData parseTradefedResult(InputStream input) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();

        // Secure StAX configuration: result XML is untrusted uploaded input.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXMLResolver((publicID, systemID, baseURI, namespace) -> null);

        XMLStreamReader reader = factory.createXMLStreamReader(input);

        String xmlSuite = null;
        String suitePlan = null;
        String suiteVersion = null;
        String suiteBuild = null;
        String hostName = null;
        String osName = null;
        String osVersion = null;
        String start = null;
        String end = null;

        String fingerprint = null;
        String securityPatch = null;
        String release = null;
        String sdk = null;
        String abis = null;
        String buildId = null;
        String buildType = null;

        int passed = 0;
        int failed = 0;
        int assumptionFailures = 0;
        int ignored = 0;
        int warnings = 0;
        int modulesDone = 0;
        int modulesTotal = 0;

        List<Map<String, Object>> modules = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        List<Map<String, Object>> incomplete = new ArrayList<>();
        List<TestResultData> testResults = new ArrayList<>();
        Map<String, Boolean> moduleDoneStates = new LinkedHashMap<>();

        int modulePassTotal = 0;
        int moduleFailTotal = 0;
        int moduleAssumptionTotal = 0;
        int moduleIgnoredTotal = 0;

        boolean inResult = false;
        boolean inSummary = false;
        boolean inBuild = false;
        boolean inFailure = false;
        boolean inStackTrace = false;

        String currentModuleName = null;
        String currentModuleAbi = "";
        String currentModuleDisplayName = null;
        int currentModulePassed = -1;
        int currentModuleFailed = 0;
        int currentModuleAssumptionFailures = 0;
        int currentModuleIgnored = 0;
        int currentModuleTests = 0;
        boolean currentModuleDone = false;

        String currentTestCaseName = "";
        TestResultData currentTest = null;
        String currentFailureMessage = "";
        StringBuilder currentStackTrace = new StringBuilder();

        try {
            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();

                    if ("Result".equals(name) && !inResult) {
                        inResult = true;
                        xmlSuite = streamAttr(reader, "suite_name");
                        suitePlan = streamAttr(reader, "suite_plan");
                        suiteVersion = streamAttr(reader, "suite_version");
                        suiteBuild = streamAttr(reader, "suite_build_number");
                        hostName = streamAttr(reader, "host_name");
                        osName = streamAttr(reader, "os_name");
                        osVersion = streamAttr(reader, "os_version");
                        start = firstNonBlank(streamAttr(reader, "start_display"), streamAttr(reader, "start"));
                        end = firstNonBlank(streamAttr(reader, "end_display"), streamAttr(reader, "end"));
                        continue;
                    }

                    if (!inResult) {
                        continue;
                    }

                    if ("Summary".equals(name)) {
                        inSummary = true;
                        passed = intStreamAttr(reader, "pass", passed);
                        failed = intStreamAttr(reader, "failed", failed);
                        assumptionFailures = intStreamAttr(reader, "assumption_failure",
                                intStreamAttr(reader, "assumption_failures", assumptionFailures));
                        ignored = intStreamAttr(reader, "ignored", ignored);
                        warnings = intStreamAttr(reader, "warning",
                                intStreamAttr(reader, "warnings", warnings));
                        modulesDone = intStreamAttr(reader, "modules_done", modulesDone);
                        modulesTotal = intStreamAttr(reader, "modules_total", modulesTotal);
                        continue;
                    }

                    if ("Build".equals(name)) {
                        inBuild = true;
                        fingerprint = streamAttr(reader, "build_fingerprint");
                        securityPatch = streamAttr(reader, "build_version_security_patch");
                        release = streamAttr(reader, "build_version_release");
                        sdk = streamAttr(reader, "build_version_sdk");
                        abis = streamAttr(reader, "build_abis");
                        buildId = firstNonBlank(streamAttr(reader, "build_id"),
                                streamAttr(reader, "build_version_incremental"));
                        buildType = streamAttr(reader, "build_type");
                        continue;
                    }

                    if ("Module".equals(name)) {
                        currentModuleName = firstNonBlank(streamAttr(reader, "name"), "Unknown Module");
                        currentModuleAbi = firstNonBlank(streamAttr(reader, "abi"), "");
                        currentModuleDisplayName = currentModuleAbi.isBlank()
                                ? currentModuleName
                                : currentModuleAbi + " " + currentModuleName;
                        currentModulePassed = intStreamAttr(reader, "pass", -1);
                        currentModuleFailed = 0;
                        currentModuleAssumptionFailures = 0;
                        currentModuleIgnored = 0;
                        currentModuleTests = 0;
                        currentModuleDone = Boolean.parseBoolean(
                                firstNonBlank(streamAttr(reader, "done"), "false"));
                        continue;
                    }

                    if ("TestCase".equals(name) && currentModuleName != null) {
                        currentTestCaseName = firstNonBlank(streamAttr(reader, "name"), "");
                        continue;
                    }

                    if ("Test".equals(name) && currentModuleName != null) {
                        String resultValue = firstNonBlank(streamAttr(reader, "result"), "");
                        String testName = firstNonBlank(streamAttr(reader, "name"), "Unknown Test");
                        String testKey = (currentModuleAbi + "|" + currentModuleName + "|" +
                                currentTestCaseName + "|" + testName).toLowerCase(Locale.ROOT);

                        currentTest = new TestResultData(
                                testKey, normalizeSuiteName(xmlSuite, suitePlan),
                                currentModuleName, currentModuleAbi, currentTestCaseName,
                                testName, resultValue, ""
                        );
                        currentFailureMessage = "";
                        currentStackTrace.setLength(0);
                        currentModuleTests++;
                        continue;
                    }

                    if ("Failure".equals(name) && currentTest != null) {
                        inFailure = true;
                        currentFailureMessage = firstNonBlank(streamAttr(reader, "message"), "");
                        continue;
                    }

                    if ("StackTrace".equals(name) && inFailure && currentTest != null) {
                        inStackTrace = true;
                        currentStackTrace.setLength(0);
                        continue;
                    }

                } else if (event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) {
                    if (inStackTrace) {
                        currentStackTrace.append(reader.getText());
                    }

                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();

                    if ("StackTrace".equals(name) && inStackTrace) {
                        inStackTrace = false;
                        String stack = currentStackTrace.toString().trim();
                        if (currentFailureMessage.isBlank()) {
                            currentFailureMessage = stack;
                        }
                    }

                    if ("Failure".equals(name) && inFailure) {
                        inFailure = false;
                        if (currentTest != null && currentFailureMessage.isBlank()) {
                            currentFailureMessage = currentStackTrace.toString().trim();
                        }
                    }

                    if ("Test".equals(name) && currentTest != null) {
                        currentTest.details = firstNonBlank(currentFailureMessage, "Test failed");

                        if ("pass".equalsIgnoreCase(currentTest.result)) {
                            modulePassTotal++;
                        } else if ("fail".equalsIgnoreCase(currentTest.result)) {
                            currentModuleFailed++;
                            moduleFailTotal++;

                            failures.add(Map.of(
                                    "suite", currentTest.suite,
                                    "module", currentModuleDisplayName,
                                    "testCase", currentTest.testCase + "#" + currentTest.name,
                                    "details", firstNonBlank(currentFailureMessage, "Test failed")
                            ));
                        } else if ("assumption_failure".equalsIgnoreCase(currentTest.result)
                                || "assumption-failure".equalsIgnoreCase(currentTest.result)) {
                            currentModuleAssumptionFailures++;
                            moduleAssumptionTotal++;
                        } else if ("ignored".equalsIgnoreCase(currentTest.result)) {
                            currentModuleIgnored++;
                            moduleIgnoredTotal++;
                        }

                        testResults.add(currentTest);
                        currentTest = null;
                    }

                    if ("TestCase".equals(name)) {
                        currentTestCaseName = "";
                    }

                    if ("Module".equals(name) && currentModuleName != null) {
                        int modulePassed = currentModulePassed >= 0
                                ? currentModulePassed
                                : Math.max(0, currentModuleTests - currentModuleFailed);

                        String moduleKey = (currentModuleAbi + "|" + currentModuleName)
                                .toLowerCase(Locale.ROOT);
                        moduleDoneStates.put(moduleKey, currentModuleDone);

                        if (!currentModuleDone) {
                            incomplete.add(Map.of(
                                    "suite", normalizeSuiteName(xmlSuite, suitePlan),
                                    "module", currentModuleDisplayName,
                                    "reason", "Module is marked done=false in Tradefed result"
                            ));
                        }

                        Map<String, Object> moduleMap = new LinkedHashMap<>();
                        moduleMap.put("name", currentModuleName);
                        moduleMap.put("abi", currentModuleAbi);
                        moduleMap.put("passed", modulePassed);
                        moduleMap.put("failed", currentModuleFailed);
                        moduleMap.put("assumptionFailures", currentModuleAssumptionFailures);
                        moduleMap.put("ignored", currentModuleIgnored);
                        moduleMap.put("totalTests", currentModuleTests);
                        moduleMap.put("done", currentModuleDone);
                        modules.add(moduleMap);

                        currentModuleName = null;
                        currentModuleAbi = "";
                        currentModuleDisplayName = null;
                    }

                    if ("Summary".equals(name)) {
                        inSummary = false;
                    }
                    if ("Build".equals(name)) {
                        inBuild = false;
                    }
                    if ("Result".equals(name)) {
                        inResult = false;
                    }
                }
            }
        } finally {
            reader.close();
        }

        String suite = normalizeSuiteName(xmlSuite, suitePlan);
        String normalizedSuitePlan = normalizeSuitePlan(suitePlan, xmlSuite);
        if (suite == null) {
            return null;
        }

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
            modulesDone = (int) modules.stream()
                    .filter(m -> Boolean.TRUE.equals(m.get("done")))
                    .count();
        }

        // Summary counts are authoritative. Older result versions may omit
        // summary pass/fail, so derive them from individual test results.
        if (passed == 0 && failed == 0 && !modules.isEmpty()) {
            passed = modulePassTotal;
            failed = moduleFailTotal;
        }

        int testCases = passed + failed + assumptionFailures + ignored;
        String status = modulesDone >= modulesTotal && modulesTotal > 0
                ? "COMPLETED" : "INCOMPLETE";

        ReportData data = new ReportData();
        data.suite = suite;
        data.plan = normalizedSuitePlan;
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
        data.buildId = buildId;
        data.buildType = buildType;
        data.testCases = testCases;
        data.status = status;
        data.moduleDetails = modules;
        data.failures = failures;
        data.incomplete = incomplete;
        data.testResults = testResults;
        data.moduleDoneStates = moduleDoneStates;
        return data;
    }

    private static String streamAttr(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return value == null || value.isBlank() ? null : value;
    }

    private static int intStreamAttr(XMLStreamReader reader, String name, int fallback) {
        String value = streamAttr(reader, name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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
     * A test is considered PASS if it passed in any execution. This prevents
     * a later rerun failure from overwriting a successful retry.
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
        String release = null, sdk = null, abis = null, buildId = null, buildType = null;

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
            buildId = firstNonBlank(buildId, report.buildId);
            buildType = firstNonBlank(buildType, report.buildType);

            moduleDoneStates.putAll(report.moduleDoneStates);

            for (TestResultData test : report.testResults) {
                // A test is considered PASS if it passed in any execution.
                // A later FAIL must not overwrite an earlier PASS from a rerun.
                TestResultData existing = testResults.get(test.key);
                if (existing == null) {
                    testResults.put(test.key, test);
                } else {
                    testResults.put(test.key, mergeTestResult(existing, test));
                }
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
        merged.buildId = buildId;
        merged.buildType = buildType;
        merged.moduleDetails = modules;
        merged.failures.addAll(failures);
        merged.incompleteModules.addAll(incomplete);
        merged.testResults.addAll(testResults.values());

        return merged;
    }

    /**
     * Reconcile repeated executions of the same test case.
     *
     * Certification reruns are attempts to recover failed tests. Therefore,
     * if the same test is PASS in any report, the final state is PASS even if
     * another report contains FAIL for that same test. If no PASS exists,
     * retain the latest observed result.
     */
    private static TestResultData mergeTestResult(TestResultData existing, TestResultData current) {
        boolean existingPass = "pass".equalsIgnoreCase(existing.result);
        boolean currentPass = "pass".equalsIgnoreCase(current.result);

        if (existingPass) {
            return existing;
        }
        if (currentPass) {
            return current;
        }

        return current;
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
     * Tradefed reports contain both Result/@suite_name and Result/@suite_plan.
     * The suite plan is the authoritative test-suite identifier for this parser.
     *
     * Examples:
     *   suite_plan="cts"        -> CTS
     *   suite_plan="gts"        -> GTS
     *   suite_plan="tvts"       -> TVTS
     *   suite_plan="cts-on-gsi" -> CTS-ON-GSI
     *
     * suite_name is used only as a fallback when suite_plan is missing.
     */
    private static String normalizeSuiteName(String suiteName, String suitePlan) {
        String normalizedPlan = suitePlan == null ? null : suitePlan.trim();
        if (normalizedPlan != null && !normalizedPlan.isBlank()) {
            return normalizedPlan.toUpperCase(Locale.ROOT);
        }

        String normalizedSuite = suiteName == null ? null : suiteName.trim();
        if (normalizedSuite == null || normalizedSuite.isBlank()) {
            return null;
        }

        return normalizedSuite.toUpperCase(Locale.ROOT);
    }

    /**
     * Preserve the normalized suite plan separately in the parsed report.
     * If a report does not provide suite_plan, fall back to suite_name.
     */
    private static String normalizeSuitePlan(String suitePlan, String suiteName) {
        String normalizedPlan = suitePlan == null ? null : suitePlan.trim();
        if (normalizedPlan != null && !normalizedPlan.isBlank()) {
            return normalizedPlan.toUpperCase(Locale.ROOT);
        }

        String normalizedSuite = suiteName == null ? null : suiteName.trim();
        return normalizedSuite == null || normalizedSuite.isBlank()
                ? null
                : normalizedSuite.toUpperCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
        String buildId;
        String buildType;
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
            parsed.buildId = buildId;
            parsed.buildType = buildType;
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
        String buildId;
        String buildType;
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
            map.put("buildId", buildId);
            map.put("buildType", buildType);
            map.put("moduleDetails", moduleDetails);
            return map;
        }

        static ParsedReport unknown() {
            return new ParsedReport(null, 0, 0, 0, 0, 0, 0, 0, 0,
                    "UNSUPPORTED", "Not detected", "Not detected");
        }
    }
}
