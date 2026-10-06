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
<title>GCT Dashboard</title>
<style>
:root{--bg:#f5f6f8;--card:#fff;--ink:#18202a;--muted:#68727d;--line:#dfe3e8;--red:#c62828;--green:#137a52;--greenbg:#e8f6ef;--redbg:#fdecec;--blue:#245ea8}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px Arial,Helvetica,sans-serif}
.wrap{max-width:1050px;margin:0 auto;padding:28px 22px 45px}.header{background:#fff;border:1px solid var(--line);padding:24px 28px;display:flex;justify-content:space-between;gap:20px;align-items:center}
h1{margin:0;font-size:26px}.subtitle{margin-top:5px;color:var(--muted)}.status{padding:12px 18px;border-radius:6px;font-weight:800;font-size:15px}
.status.pass{background:var(--greenbg);color:var(--green)}.status.fail{background:var(--redbg);color:var(--red)}
.section{background:var(--card);border:1px solid var(--line);margin-top:16px;padding:20px}.title{font-weight:800;font-size:13px;margin-bottom:14px}
.summary{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.field{border-left:3px solid var(--line);padding-left:10px}.field label{display:block;color:var(--muted);font-size:10px;font-weight:800;text-transform:uppercase;margin-bottom:5px}.field strong{font-size:13px;word-break:break-word}
.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse}th{background:#eef1f4;text-align:left;font-size:11px;text-transform:uppercase;color:#56616c;padding:11px}td{padding:11px;border-bottom:1px solid var(--line)}tbody tr:hover{background:#fafbfc}
.pass-text{color:var(--green);font-weight:800}.fail-text{color:var(--red);font-weight:800}.inc-text{color:var(--red);font-weight:800}
.issue{padding:10px 0;border-bottom:1px solid var(--line)}.issue:last-child{border-bottom:0}.issue strong{display:block}.issue span{display:block;color:var(--muted);margin-top:4px;font-size:12px}
.empty{color:var(--green);background:var(--greenbg);padding:12px;border-radius:4px}.footer{text-align:center;color:var(--muted);font-size:11px;margin-top:18px}
@media(max-width:700px){.header{display:block}.status{display:inline-block;margin-top:15px}.summary{grid-template-columns:1fr}.wrap{padding:15px 10px}}
@media print{body{background:#fff}.wrap{max-width:none;padding:0}.section,.header{break-inside:avoid}}
</style></head><body><div class="wrap">
""");

        h.append("<div class='header'><div><h1>GCT Certification Dashboard</h1>")
                .append("<div class='subtitle'>GCT test execution summary for stakeholder review</div></div>")
                .append("<div class='status ").append(overallPass ? "pass" : "fail").append("'>")
                .append(fingerprintMismatch ? "BUILD MISMATCH" : (overallPass ? "PASS" : "ATTENTION REQUIRED")).append("</div></div>");

        h.append("<section class='section'><div class='title'>Build Information</div><div class='summary'>")
                .append(field("Build fingerprint", fingerprint))
                .append(field("Security patch", patch))
                .append(field("Android version", androidVersion))
                .append("</div>");
        if (fingerprintMismatch) {
            h.append("<div class='issue'><strong>Build mismatch detected</strong><span>Reports from different build fingerprints were uploaded. They were kept separate and were not merged.</span></div>");
        }
        h.append("</section>");

        h.append("<section class='section'><div class='title'>Suite summary</div><div class='table-wrap'><table>")
                .append("<thead><tr><th>Suite</th><th>Mods</th><th>Done</th><th>Inc</th><th>Cases</th><th>Pass</th><th>Fail</th></tr></thead><tbody>");

        for (Map<String, Object> suite : suites) {
            int mods = number(suite.get("modules"));
            int done = number(suite.get("completedModules"));
            int inc = Math.max(0, mods - done);
            int cases = number(suite.get("testCases"));
            int pass = number(suite.get("passed"));
            int fail = number(suite.get("failed"));
            h.append("<tr><td><strong>").append(e(text(suite.get("name")))).append("</strong></td>")
                    .append("<td>").append(mods).append("</td><td>").append(done).append("</td>")
                    .append("<td class='").append(inc == 0 ? "pass-text" : "inc-text").append("'>").append(inc).append("</td>")
                    .append("<td>").append(cases).append("</td><td class='pass-text'>").append(pass).append("</td>")
                    .append("<td class='").append(fail == 0 ? "pass-text" : "fail-text").append("'>").append(fail).append("</td></tr>");
        }
        h.append("</tbody></table></div></section>");

        h.append("<section class='section'><div class='title'>Incomplete modules</div>");
        if (incomplete.isEmpty()) {
            h.append("<div class='empty'>No incomplete modules.</div>");
        } else {
            for (Map<String, Object> item : incomplete) {
                h.append("<div class='issue'><strong>[")
                        .append(e(text(item.get("suite")))).append("] ")
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
                h.append("<div class='issue'><strong> - [")
                        .append(e(text(failure.get("suite")))).append("] ")
                        .append(e(text(failure.get("module")))).append(" :: ")
                        .append(e(text(failure.get("testCase")))).append("</strong>");
                if (!text(failure.get("details")).isBlank()) {
                    h.append("<span>").append(e(text(failure.get("details")))).append("</span>");
                }
                h.append("</div>");
            }
        }
        h.append("</section>");

        h.append("<div class='footer'>Generated ").append(e(generated))
                .append(" · GCT Report Parser</div></div></body></html>");
        return h.toString();
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
