package com.gct.parser;

import java.util.*;

public final class HtmlReportBuilder {
    private HtmlReportBuilder() {}
    public static String build(Map<String,Object> d) {
        return "<!doctype html><html><head><meta charset='UTF-8'><title>GCT Certification Dashboard</title></head><body>"
                + "<h1>GCT Certification Dashboard</h1>"
                + "<p><b>Build Fingerprint:</b> " + e(d.get("buildFingerprint")) + "</p>"
                + "<p><b>Security Patch:</b> " + e(d.get("securityPatch")) + "</p>"
                + "<pre>" + e(d) + "</pre></body></html>";
    }
    private static String e(Object o){return String.valueOf(o==null?"":o).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
