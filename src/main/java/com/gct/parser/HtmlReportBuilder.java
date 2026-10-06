package com.gct.parser;

import java.time.Instant;
import java.util.*;

public final class HtmlReportBuilder {
    private HtmlReportBuilder() {}

    public static String build(Map<String, Object> dashboard) {
        List<Map<String, Object>> suites = maps(dashboard.get("suites"));
        List<Map<String, Object>> failures = maps(dashboard.get("failures"));
        List<Map<String, Object>> incomplete = maps(dashboard.get("incompleteModules"));

        Map<String, Object> overall = map(dashboard.get("overall"));

        int total = number(overall.get("totalTests"));
        int passed = number(overall.get("passed"));
        int failed = number(overall.get("failed"));
        int warnings = number(overall.get("warnings"));
        int blocked = incomplete.size();

        boolean overallPass = failed == 0 && blocked == 0;
        String overallStatus = overallPass ? "PASS" : "ATTENTION REQUIRED";

        String testSuites = suites.stream()
                .map(s -> text(s.get("name")))
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Not detected");

        String generated = text(dashboard.get("generatedAt"));
        if (generated.isBlank()) {
            generated = Instant.now().toString();
        }

        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>GCT Certification Dashboard</title>
                  <style>
                    :root{--ink:#151515;--muted:#6d675f;--paper:#fffdf8;--cream:#f1e8dc;--line:#ddd3c6;--accent:#ff5b35;--green:#16815c;--green-bg:#e3f2eb;--red:#b9382e;--red-bg:#f9e2de;--amber:#9b681c;--amber-bg:#faefd9;--dark:#11100f}
                    *{box-sizing:border-box}
                    body{margin:0;background:var(--cream);color:var(--ink);font-family:Arial,Helvetica,sans-serif}
                    .wrap{max-width:1180px;margin:auto;padding:34px 24px 60px}
                    .header{background:var(--dark);color:white;padding:34px 38px;display:flex;justify-content:space-between;gap:30px}
                    .eyebrow{font-size:11px;letter-spacing:2px;color:var(--accent);font-weight:800}
                    h1{font-size:38px;margin:10px 0 8px}
                    .subtitle{color:#bdb6ae;font-size:14px;line-height:1.5}
                    .status{min-width:210px;align-self:center;text-align:center;padding:18px 20px;border:1px solid #514b45}
                    .status span{display:block;color:#a8a098;font-size:10px;letter-spacing:1.5px;margin-bottom:8px}
                    .status strong{font-size:22px}
                    .status.pass{color:#79ddb5}.status.attention{color:#ffb56d}
                    .section{background:var(--paper);border:1px solid var(--line);margin-top:16px;padding:24px}
                    .section-title{font-size:12px;letter-spacing:1.6px;font-weight:800;color:var(--muted);margin-bottom:16px}
                    .identity{display:grid;grid-template-columns:1fr 1fr;gap:18px}
                    .field{border-top:2px solid var(--line);padding-top:11px}
                    .field label{display:block;color:#918980;font-size:10px;letter-spacing:1px;font-weight:800;margin-bottom:6px}
                    .field strong{font-size:14px;line-height:1.45;word-break:break-word}
                    .suite-header{display:flex;justify-content:space-between;gap:15px;align-items:center}
                    .suite-name{font-size:24px;font-weight:900}
                    .pill{font-size:10px;font-weight:900;padding:7px 10px}
                    .pill.pass{background:var(--green-bg);color:var(--green)}
                    .pill.fail{background:var(--red-bg);color:var(--red)}
                    .pill.warn{background:var(--amber-bg);color:var(--amber)}
                    .metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:16px}
                    .metric{background:#f4eee6;padding:15px}
                    .metric label{display:block;color:#8c847b;font-size:9px;letter-spacing:1px}
                    .metric strong{display:block;font-size:22px;margin-top:5px}
                    .suite-info{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;margin-top:14px}
                    .info{padding:10px 0;border-top:1px solid #eee6dc}
                    .info label{display:block;color:#918980;font-size:9px;letter-spacing:1px}
                    .info div{font-size:12px;margin-top:4px;line-height:1.45;word-break:break-word}
                    .table-wrap{overflow:auto;margin-top:18px}
                    table{width:100%;border-collapse:collapse;font-size:11px}
                    th{text-align:left;background:#e9dfd2;padding:10px}
                    td{border-bottom:1px solid #eee6dc;padding:9px}
                    tr.bad{background:#fff0ed}
                    .empty{color:var(--green);background:var(--green-bg);padding:14px;font-size:12px}
                    .issue{border-top:1px solid #eee6dc;padding:13px 0}
                    .issue strong{display:block;font-size:12px}
                    .issue span{display:block;color:var(--muted);font-size:11px;margin-top:5px;line-height:1.45}
                    .footer{margin-top:24px;text-align:center;color:#7d756d;font-size:10px;line-height:1.6}
                    @media(max-width:800px){.header{display:block}.status{margin-top:22px}.metrics{grid-template-columns:repeat(2,1fr)}.identity,.suite-info{grid-template-columns:1fr}}
                    @media(max-width:500px){.wrap{padding:15px 10px}.header,.section{padding:20px}.metrics{grid-template-columns:1fr 1fr}h1{font-size:30px}}
                    @media print{body{background:white}.wrap{max-width:none;padding:0}.header{break-inside:avoid}.section{break-inside:avoid}.footer{display:none}}
                  </style>
                </head>
                <body>
                <div class="wrap">
                """);

        html.append("<header class="header">")
                .append("<div><div class="eyebrow">ANDROID CERTIFICATION · STAKEHOLDER REPORT</div>")
                .append("<h1>GCT Certification Dashboard</h1>")
                .append("<div class="subtitle">Release-readiness summary generated from the uploaded certification reports.</div></div>")
                .append("<div class="status ").append(overallPass ? "pass" : "attention").append("">")
                .append("<span>OVERALL STATUS</span><strong>").append(e(overallStatus)).append("</strong></div>")
                .append("</header>");

        html.append("<section class="section"><div class="section-title">REPORT SUMMARY</div>")
                .append("<div class="identity">")
                .append(field("TEST SUITE", testSuites))
                .append(field("GENERATED", generated))
                .append(field("BUILD FINGERPRINT", text(dashboard.get("buildFingerprint"))))
                .append(field("SECURITY PATCH", text(dashboard.get("securityPatch"))))
                .append("</div></section>");

        html.append("<section class="section"><div class="section-title">OVERALL RESULTS</div>")
                .append("<div class="metrics">")
                .append(metric("TOTAL TESTS", total))
                .append(metric("PASSED", passed))
                .append(metric("FAILED", failed))
                .append(metric("INCOMPLETE MODULES", blocked))
                .append("</div>")
                .append("<div class="suite-info">")
                .append(info("WARNINGS", String.valueOf(warnings)))
                .append(info("PASS RATE", total == 0 ? "0%" : String.format(Locale.ROOT, "%.1f%%", passed * 100.0 / total)))
                .append("</div></section>");

        for (Map<String, Object> suite : suites) {
            html.append(renderSuite(suite));
        }

        html.append("<section class="section"><div class="section-title">FAILED TEST CASES</div>");
        if (failures.isEmpty()) {
            html.append("<div class="empty">No failed test cases detected.</div>");
        } else {
            for (Map<String, Object> failure : failures) {
                html.append("<div class="issue"><strong>")
                        .append(e(text(failure.get("suite")))).append(" · ")
                        .append(e(text(failure.get("module")))).append(" · ")
                        .append(e(text(failure.get("testCase"))))
                        .append("</strong><span>")
                        .append(e(text(failure.get("details"))))
                        .append("</span></div>");
            }
        }
        html.append("</section>");

        html.append("<section class="section"><div class="section-title">INCOMPLETE MODULES</div>");
        if (incomplete.isEmpty()) {
            html.append("<div class="empty">No incomplete modules detected. All reported modules are complete.</div>");
        } else {
            for (Map<String, Object> item : incomplete) {
                html.append("<div class="issue"><strong>")
                        .append(e(text(item.get("suite")))).append(" · ")
                        .append(e(text(item.get("module"))))
                        .append("</strong><span>")
                        .append(e(text(item.get("reason"))))
                        .append("</span></div>");
            }
        }
        html.append("</section>");

        html.append("<div class="footer">GCT Report Parser · Generated ")
                .append(e(generated))
                .append(" · This report is intended for certification/release-readiness stakeholder review.</div>")
                .append("</div></body></html>");

        return html.toString();
    }

    private static String renderSuite(Map<String, Object> suite) {
        int passed = number(suite.get("passed"));
        int failed = number(suite.get("failed"));
        int warnings = number(suite.get("warnings"));
        int modules = number(suite.get("modules"));
        int completed = number(suite.get("completedModules"));
        int tests = number(suite.get("testCases"));

        boolean pass = failed == 0 && completed >= modules && modules > 0;
        String status = pass ? "PASS" : "ATTENTION";

        StringBuilder html = new StringBuilder();
        html.append("<section class="section"><div class="suite-header"><div>")
                .append("<div class="section-title">TEST SUITE</div>")
                .append("<div class="suite-name">").append(e(text(suite.get("name")))).append("</div>")
                .append("</div><span class="pill ").append(pass ? "pass" : "warn").append("">")
                .append(status).append("</span></div>");

        html.append("<div class="metrics">")
                .append(metric("TOTAL TESTS", tests))
                .append(metric("PASSED", passed))
                .append(metric("FAILED", failed))
                .append(metric("MODULES", completed + " / " + modules))
                .append("</div>");

        html.append("<div class="suite-info">")
                .append(info("SUITE / PLAN", text(suite.get("name")) + " / " + text(suite.get("plan"))))
                .append(info("SUITE / BUILD", text(suite.get("version")) + " / " + text(suite.get("buildNumber"))))
                .append(info("HOST INFO", text(suite.get("hostInfo"))))
                .append(info("START / END", text(suite.get("start")) + " / " + text(suite.get("end"))))
                .append(info("FINGERPRINT", text(suite.get("fingerprint"))))
                .append(info("SECURITY PATCH", text(suite.get("securityPatch"))))
                .append(info("RELEASE (SDK)", text(suite.get("release")) + " (" + text(suite.get("sdk")) + ")"))
                .append(info("ABIs", text(suite.get("abis"))))
                .append(info("WARNINGS", String.valueOf(warnings)))
                .append("</div>");

        List<Map<String, Object>> modulesList = maps(suite.get("moduleDetails"));
        if (!modulesList.isEmpty()) {
            html.append("<div class="table-wrap"><table><thead><tr><th>Module</th><th>Passed</th><th>Failed</th><th>Total</th><th>Done</th></tr></thead><tbody>");
            for (Map<String, Object> module : modulesList) {
                boolean done = Boolean.TRUE.equals(module.get("done"));
                html.append("<tr class="").append(done ? "" : "bad").append("">")
                        .append("<td>").append(e((text(module.get("abi")) + " " + text(module.get("name"))).trim())).append("</td>")
                        .append("<td>").append(number(module.get("passed"))).append("</td>")
                        .append("<td>").append(number(module.get("failed"))).append("</td>")
                        .append("<td>").append(number(module.get("totalTests"))).append("</td>")
                        .append("<td>").append(done ? "true" : "false").append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }

        html.append("</section>");
        return html.toString();
    }

    private static String field(String label, String value) {
        return "<div class="field"><label>" + e(label) + "</label><strong>" + e(value) + "</strong></div>";
    }

    private static String metric(String label, Object value) {
        return "<div class="metric"><label>" + e(label) + "</label><strong>" + e(value) + "</strong></div>";
    }

    private static String info(String label, String value) {
        return "<div class="info"><label>" + e(label) + "</label><div>" + e(value) + "</div></div>";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private static int number(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String e(Object value) {
        return text(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
