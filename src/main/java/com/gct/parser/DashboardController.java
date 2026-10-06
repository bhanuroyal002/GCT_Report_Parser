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
        String filenameSuite = detectSuite(file.getOriginalFilename());
        List<ReportData> candidates = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (!name.endsWith("test_result.xml")) {
                    continue;
                }

                // Read the complete XML entry before parsing it. This prevents
                // one XML parse failure from aborting the remaining split reports.
                byte[] xmlBytes = zis.readAllBytes();

                try (InputStream xmlInput = new ByteArrayInputStream(xmlBytes)) {
                    ReportData candidate = parseTradefedResult(xmlInput, filenameSuite);
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                } catch (Exception ignoredEntry) {
                    // Ignore only this result entry and continue scanning the ZIP.
                }
            }
        } catch (Exception ignoredZip) {
            // A malformed/unsupported ZIP is simply ignored; parsed entries are retained.
        }

        if (candidates.isEmpty()) {
            return ParsedReport.unknown();
        }

        // A ZIP may contain several Tradefed split results. Merge all of them.
        List<ParsedReport> parsedReports = new ArrayList<>();
        for (ReportData candidate : candidates) {
            parsedReports.add(candidate.toParsedReport());
        }
        return mergeReports(parsedReports);
    }

    private ReportData parseTradefedResult(InputStream input, String filenameSuite) throws Exception {
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

        // CTS-on-GSI reports can contain suite_name="CTS" inside the XML.
        // When the uploaded filename identifies the report as CTS-on-GSI,
        // preserve that distinction instead of collapsing it into CTS.
        String suite;
        if ("CTS-on-GSI".equals(filenameSuite)) {
            suite = "CTS-on-GSI";
        } else if ("CTS-Verifier".equals(filenameSuite)
                || "CTS_VERIFIER".equalsIgnoreCase(xmlSuite)
                || "CTS-VERIFIER".equalsIgnoreCase(xmlSuite)) {
            suite = "CTS-Verifier";
        } else {
            suite = firstNonBlank(filenameSuite, xmlSuite);
        }

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
                    moduleTests++;

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
        return data;
    }

    /**
     * Merge multiple result ZIPs belonging to the same suite.
     *
     * Modules are treated as the unit of uniqueness. If the same module appears
     * in more than one ZIP, keep the richer record instead of counting it twice.
     * This is important when users upload split/partial result archives.
     */
    private static ParsedReport mergeReports(List<ParsedReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return ParsedReport.unknown();
        }
        if (reports.size() == 1) {
            return reports.get(0);
        }

        Map<String, Map<String, Object>> modules = new LinkedHashMap<>();
        Map<String, Map<String, Object>> uniqueFailures = new LinkedHashMap<>();

        String suite = reports.get(0).suite;
        String plan = null, version = null, buildNumber = null, hostInfo = null;
        String start = null, end = null, fingerprint = null, patch = null;
        String release = null, sdk = null, abis = null;
        int assumptionFailureCount = 0;
        int ignoredCount = 0;
        int warningCount = 0;

        for (ParsedReport report : reports) {
            plan = firstNonBlank(plan, report.plan);
            version = firstNonBlank(version, report.version);
            buildNumber = firstNonBlank(buildNumber, report.buildNumber);
            hostInfo = firstNonBlank(hostInfo, report.hostInfo);
            start = firstNonBlank(start, report.start);
            end = firstNonBlank(end, report.end);
            fingerprint = firstNonBlank(
                    fingerprint,
                    "Not detected".equals(report.fingerprint) ? null : report.fingerprint
            );
            patch = firstNonBlank(
                    patch,
                    "Not detected".equals(report.patch) ? null : report.patch
            );
            release = firstNonBlank(release, report.release);
            sdk = firstNonBlank(sdk, report.sdk);
            abis = firstNonBlank(abis, report.abis);
            assumptionFailureCount += report.assumptionFailures;
            ignoredCount += report.ignored;
            warningCount += report.warnings;

            for (Map<String, Object> module : report.moduleDetails) {
                String key = (textValue(module.get("abi")) + "|" + textValue(module.get("name")))
                        .toLowerCase(Locale.ROOT);
                Map<String, Object> existing = modules.get(key);

                if (existing == null || isRicherModule(module, existing)) {
                    modules.put(key, new LinkedHashMap<>(module));
                }
            }

            for (Map<String, Object> failure : report.failures) {
                String key = String.join("|",
                        textValue(failure.get("suite")),
                        textValue(failure.get("module")),
                        textValue(failure.get("testCase"))
                );
                uniqueFailures.putIfAbsent(key, failure);
            }
        }

        int modulePassed = 0;
        int moduleFailed = 0;
        int moduleTests = 0;
        int done = 0;

        for (Map<String, Object> module : modules.values()) {
            modulePassed += intValue(module.get("passed"));
            moduleFailed += intValue(module.get("failed"));
            moduleTests += intValue(module.get("totalTests"));
            if (Boolean.TRUE.equals(module.get("done"))) {
                done++;
            }
        }

        int modulesTotal;
        int modulesDone;
        int passed;
        int failed;
        int assumptionFailures;
        int ignored;
        int testCases;

        if (!modules.isEmpty()) {
            modulesTotal = modules.size();
            modulesDone = done;
            passed = modulePassed;
            failed = moduleFailed;
            assumptionFailures = modules.values().stream().mapToInt(m -> intValue(m.get("assumptionFailures"))).sum();
            ignored = modules.values().stream().mapToInt(m -> intValue(m.get("ignored"))).sum();
            testCases = moduleTests;
        } else {
            modulesTotal = reports.stream().mapToInt(r -> r.modules).sum();
            modulesDone = reports.stream().mapToInt(r -> r.completedModules).sum();
            passed = reports.stream().mapToInt(r -> r.passed).sum();
            failed = reports.stream().mapToInt(r -> r.failed).sum();
            assumptionFailures = assumptionFailureCount;
            ignored = ignoredCount;
            testCases = passed + failed + assumptionFailures + ignored;
        }

        String mergedFingerprint = firstNonBlank(fingerprint, "Not detected");
        String mergedPatch = firstNonBlank(patch, "Not detected");
        String status = modulesTotal > 0 && modulesDone >= modulesTotal
                ? "COMPLETED" : "INCOMPLETE";

        ParsedReport merged = new ParsedReport(
                suite, modulesTotal, modulesDone, testCases,
                passed, failed, assumptionFailures, ignored, warningCount, status,
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
        merged.moduleDetails = new ArrayList<>(modules.values());

        for (Map<String, Object> failure : uniqueFailures.values()) {
            merged.failures.add(failure);
        }

        for (Map<String, Object> module : merged.moduleDetails) {
            if (!Boolean.TRUE.equals(module.get("done"))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("suite", merged.suite);
                item.put("module", displayModuleName(module));
                item.put("failed", intValue(module.get("failed")));
                item.put("reason", "Module is marked done=false in Tradefed result");
                merged.incompleteModules.add(item);
            }
        }

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

    private static String detectSuite(String name) {
        if (name == null) {
            return null;
        }

        String normalized = name.toLowerCase(Locale.ROOT)
                .replace("_", "-")
                .replace(" ", "-")
                .replace(".zip", "");

        if (normalized.contains("cts-on-gsi") || normalized.contains("ctsongsi")) {
            return "CTS-on-GSI";
        }

        // CTS Verifier reports use CTS_VERIFIER in the XML/filename.
        if (normalized.contains("cts-verifier")
                || normalized.contains("ctsverifier")
                || normalized.contains("cts_verifier")) {
            return "CTS-Verifier";
        }

        for (String suite : SUPPORTED) {
            String key = suite.toLowerCase(Locale.ROOT).replace("-", "");
            if (normalized.replace("-", "").contains(key)) {
                return suite;
            }
        }

        return null;
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
            return parsed;
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
