package com.gct.parser;

import java.time.Instant;
import java.util.*;

public final class HtmlReportBuilder {
    private HtmlReportBuilder() {}

    public static String build(Map<String, Object> dashboard) {
        List<Map<String, Object>> suites = maps(dashboard.get("suites"));
        List<Map<String, Object>> failures = maps(dashboard.get("failures"));
        List<Map<String, Object>> incomplete = maps(dashboard.get("incompleteModules"));
        List<Map<String, Object>> builds = maps(dashboard.get("builds"));
        Map<String, Object> overall = map(dashboard.get("overall"));

        int total = number(overall.get("totalTests"));
        int passed = number(overall.get("passed"));
        int failed = number(overall.get("failed"));
        int assumptionFailures = number(overall.get("assumptionFailures"));
        int ignored = number(overall.get("ignored"));
        boolean fingerprintMismatch = Boolean.TRUE.equals(overall.get("fingerprintMismatch"));
        boolean overallPass = failed == 0 && incomplete.isEmpty() && !fingerprintMismatch;

        String fingerprint = firstValue(dashboard.get("buildFingerprint"), suites, "fingerprint");
        String patch = firstValue(dashboard.get("securityPatch"), suites, "securityPatch");
        String androidVersion = firstValue("", suites, "release");
        String generated = text(dashboard.get("generatedAt"));
        if (generated.isBlank()) generated = Instant.now().toString();

        StringBuilder h = new StringBuilder();
        h.append("""
<!doctype html><html lang="en"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>GCT Report Parser · Certification Intelligence</title>
<style>
:root{--bg:#f4f7fb;--card:#fff;--ink:#172033;--muted:#667085;--line:#dfe5ef;--navy:#172554;--blue:#2563eb;--cyan:#0891b2;--purple:#7c3aed;--orange:#f97316;--red:#dc2626;--green:#059669;--greenbg:#e9f9f2;--redbg:#fff0f0;--amber:#d97706;--amberbg:#fff7e6}
*{box-sizing:border-box}body{margin:0;background:linear-gradient(135deg,#f4f7fb 0%,#eef4ff 48%,#f8f5ff 100%);color:var(--ink);font:14px Arial,Helvetica,sans-serif}
.wrap{max-width:1100px;margin:0 auto;padding:28px 22px 45px}.header{position:relative;overflow:hidden;background:linear-gradient(120deg,#172554 0%,#1d4ed8 52%,#7c3aed 100%);color:#fff;border:0;border-radius:14px;padding:28px 30px;display:flex;justify-content:space-between;gap:20px;align-items:center;box-shadow:0 12px 30px rgba(37,99,235,.16)}
.header:after{content:"";position:absolute;width:220px;height:220px;border-radius:50%;right:-70px;top:-120px;background:rgba(255,255,255,.10)}
h1{margin:0;font-size:28px;letter-spacing:-.02em}.subtitle{margin-top:7px;color:#dbeafe;font-size:13px}.status{position:relative;z-index:1;padding:11px 16px;border-radius:999px;font-weight:900;font-size:13px;letter-spacing:.03em;box-shadow:0 4px 14px rgba(0,0,0,.12)}
.status.pass{background:#d1fae5;color:#047857}.status.fail{background:#fee2e2;color:#b91c1c}
.section{background:rgba(255,255,255,.96);border:1px solid var(--line);border-radius:12px;margin-top:16px;padding:22px;box-shadow:0 5px 18px rgba(23,37,84,.05)}.title{font-weight:900;font-size:14px;margin-bottom:16px;padding-left:12px;border-left:4px solid var(--blue);color:var(--navy);letter-spacing:.01em}
.summary{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.field{border:1px solid var(--line);border-top:3px solid var(--cyan);border-radius:9px;padding:13px 14px;background:linear-gradient(180deg,#fff,#f8fbff)}.field:nth-child(2){border-top-color:var(--purple)}.field:nth-child(3){border-top-color:var(--orange)}.field label{display:block;color:var(--muted);font-size:10px;font-weight:900;text-transform:uppercase;letter-spacing:.06em;margin-bottom:7px}.field strong{font-size:13px;word-break:break-word;line-height:1.45}
.table-wrap{overflow:auto;border:1px solid var(--line);border-radius:9px}table{width:100%;border-collapse:collapse;background:#fff}th{background:linear-gradient(90deg,#eaf2ff,#f2edff);text-align:left;font-size:10px;text-transform:uppercase;color:#475467;padding:12px;letter-spacing:.04em;white-space:nowrap}td{padding:12px;border-bottom:1px solid #edf0f5}tbody tr:nth-child(even){background:#fafcff}tbody tr:hover{background:#f1f6ff}tbody tr:last-child td{border-bottom:0}
.pass-text{color:var(--green);font-weight:900}.fail-text{color:var(--red);font-weight:900}.inc-text{color:var(--amber);font-weight:900}
.issue{padding:13px 14px;margin:8px 0;border:1px solid #e5eaf2;border-left:4px solid var(--red);border-radius:8px;background:#fffafa}.issue:last-child{margin-bottom:0}.issue strong{display:block;line-height:1.45}.issue span{display:block;color:var(--muted);margin-top:5px;font-size:12px;line-height:1.45}
.empty{color:var(--green);background:var(--greenbg);border:1px solid #b9ead5;padding:13px;border-radius:8px;font-weight:800}.footer{text-align:center;color:var(--muted);font-size:11px;line-height:1.7;margin-top:26px;padding:10px 0}.footer div+div{margin-top:5px}
@media(max-width:700px){.header{display:block;padding:23px}.status{display:inline-block;margin-top:16px}.summary{grid-template-columns:1fr}.wrap{padding:15px 10px}.section{padding:16px}}
@media print{body{background:#fff}.wrap{max-width:none;padding:0}.section,.header{break-inside:avoid;box-shadow:none}.header{print-color-adjust:exact;-webkit-print-color-adjust:exact}}
</style></head><body><div class="wrap">
""");

        h.append("<div class='header'><div><h1>GCT Report Parser</h1>")
                .append("<div class='subtitle'>Certification Intelligence Dashboard · Test execution summary for stakeholder review</div></div>")
                .append("<div class='status ").append(overallPass ? "pass" : "fail").append("'>")
                .append(fingerprintMismatch ? "BUILD MISMATCH" : (overallPass ? "PASS" : "ATTENTION REQUIRED")).append("</div></div>");

        h.append("<section class='section'><div class='title'>Build Information</div>");
        if (fingerprintMismatch && !builds.isEmpty()) {
            h.append("<div class='table-wrap'><table><thead><tr><th>Build</th><th>Suite</th><th>Fingerprint</th><th>Android Version</th><th>Security Patch</th></tr></thead><tbody>");
            for (int i = 0; i < builds.size(); i++) {
                Map<String, Object> build = builds.get(i);
                h.append("<tr><td><strong>Build ").append((char) ('A' + i)).append("</strong></td>")
                        .append("<td>").append(e(joinValues(build.get("suites")))).append("</td>")
                        .append("<td>").append(e(text(build.get("fingerprint")))).append("</td>")
                        .append("<td>").append(e(text(build.get("androidVersion")))).append("</td>")
                        .append("<td>").append(e(text(build.get("securityPatch")))).append("</td></tr>");
            }
            h.append("</tbody></table></div>");
        } else {
            h.append("<div class='summary'>")
                    .append(field("Build fingerprint", fingerprint))
                    .append(field("Security patch", patch))
                    .append(field("Android version", androidVersion))
                    .append("</div>");
        }
        h.append("</section>");

        if (fingerprintMismatch) {
            h.append("<div class='footer'><strong>GCT Report Parser</strong> · Certification Intelligence<br>© 2026 CERT_Team · Internal Certification Tool<br>Build mismatch detected. Test-case and module metrics were not included because the uploaded reports belong to different builds.</div></div></body></html>");
            return h.toString();
        }

        h.append("<section class='section'><div class='title'>Suite summary</div><div class='table-wrap'><table>")
                .append("<thead><tr><th>Suite</th><th>Mods</th><th>Done</th><th>Inc</th><th>Passed</th><th>Failed</th><th>Assumption Failure</th><th>Ignored</th><th>Total Tests</th></tr></thead><tbody>");

        for (Map<String, Object> suite : suites) {
            int mods = number(suite.get("modules"));
            int done = number(suite.get("completedModules"));
            int inc = Math.max(0, mods - done);
            int cases = number(suite.get("testCases"));
            int pass = number(suite.get("passed"));
            int fail = number(suite.get("failed"));
            int assumption = number(suite.get("assumptionFailures"));
            int ignoredCases = number(suite.get("ignored"));
            h.append("<tr><td><strong>").append(e(text(suite.get("name")))).append("</strong></td>")
                    .append("<td>").append(mods).append("</td><td>").append(done).append("</td>")
                    .append("<td class='").append(inc == 0 ? "pass-text" : "inc-text").append("'>").append(inc).append("</td>")
                    .append("<td class='pass-text'>").append(pass).append("</td>")
                    .append("<td class='").append(fail == 0 ? "pass-text" : "fail-text").append("'>").append(fail).append("</td>")
                    .append("<td>").append(assumption).append("</td><td>").append(ignoredCases).append("</td>")
                    .append("<td>").append(cases).append("</td></tr>");
        }
        h.append("</tbody></table></div></section>");

        h.append("<section class='section'><div class='title'>Incomplete modules</div>");
        if (incomplete.isEmpty()) {
            h.append("<div class='empty'>No incomplete modules.</div>");
        } else {
            for (Map<String, Object> item : incomplete) {
                h.append("<div class='issue'><strong>")
                        .append(e(text(item.get("suite")))).append(" ")
                        .append(e(text(item.get("module")))).append("</strong>");
                int failedModules = number(item.get("failed"));
                if (failedModules > 0) {
                    h.append("<span>fail=").append(failedModules).append("</span>");
                } else if (!text(item.get("reason")).isBlank()) {
                    h.append("<span>").append(e(text(item.get("reason")))).append("</span>");
                }
                h.append("</div>");
            }
        }
        h.append("</section>");

        h.append("<section class='section'><div class='title'>Failed test cases</div>");
        if (failures.isEmpty()) {
            h.append("<div class='empty'>No failed test cases.</div>");
        } else {
            for (Map<String, Object> failure : failures) {
                h.append("<div class='issue'><strong> - ")
                        .append(e(text(failure.get("suite")))).append(" ")
                        .append(e(text(failure.get("module")))).append(" :: ")
                        .append(e(text(failure.get("testCase")))).append("</strong>");
                if (!text(failure.get("details")).isBlank()) {
                    h.append("<span>").append(e(text(failure.get("details")))).append("</span>");
                }
                h.append("</div>");
            }
        }
        h.append("</section>");

        h.append("<div class='footer'><div>Generated ").append(e(generated))
                .append(" · GCT Report Parser · Certification Intelligence</div>")
                .append("<div>© 2026 CERT_Team · Internal Certification Tool</div></div></div></body></html>");
        return h.toString();
    }

    private static String joinValues(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .filter(v -> !v.isBlank())
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        return text(value);
    }

    private static String field(String label, String value) {
        return "<div class='field'><label>" + e(label) + "</label><strong>" + e(value) + "</strong></div>";
    }

    private static String firstValue(Object rootValue, List<Map<String, Object>> suites, String key) {
        String root = text(rootValue);
        if (!root.isBlank() && !"Not detected".equalsIgnoreCase(root)) return root;
        for (Map<String, Object> suite : suites) {
            String value = text(suite.get(key));
            if (!value.isBlank() && !"Not detected".equalsIgnoreCase(value)) return value;
        }
        return "Not detected";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        return result;
    }

    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : source.entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
            return result;
        }
        return Map.of();
    }

    private static int number(Object value) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return 0; }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static String e(Object value) {
        return text(value).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }
}
