package com.gct.parser;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private static final List<String> SUPPORTED = List.of("CTS", "GTS", "TVTS", "STS", "VTS", "CTS-on-GSI");

    @PostMapping(value="/analyze", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String,Object>> analyze(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0)
            return ResponseEntity.badRequest().body(Map.of("error","Upload at least one report ZIP file."));

        List<Map<String,Object>> suites = new ArrayList<>();
        List<Map<String,Object>> incomplete = new ArrayList<>();
        List<Map<String,Object>> failures = new ArrayList<>();
        String fingerprint="Not detected", patch="Not detected";

        for (MultipartFile file : files) {
            ParsedReport p = parse(file);
            if (p.suite == null) continue;
            suites.add(p.toMap());
            incomplete.addAll(p.incomplete);
            failures.addAll(p.failures);
            if (!"Not detected".equals(p.fingerprint)) fingerprint=p.fingerprint;
            if (!"Not detected".equals(p.patch)) patch=p.patch;
        }

        int total=suites.stream().mapToInt(s -> n(s.get("testCases"))).sum();
        int passed=suites.stream().mapToInt(s -> n(s.get("passed"))).sum();
        int failed=suites.stream().mapToInt(s -> n(s.get("failed"))).sum();

        Map<String,Object> out=new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("buildFingerprint", fingerprint);
        out.put("securityPatch", patch);
        out.put("overall", Map.of("totalTests",total,"passed",passed,"failed",failed,"blocked",incomplete.size()));
        out.put("suites",suites);
        out.put("incompleteModules",incomplete);
        out.put("failures",failures);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/dashboard")
    public Map<String,Object> dashboard() {
        return Map.of("generatedAt",Instant.now().toString(),"buildFingerprint","Not detected",
                "securityPatch","Not detected","overall",Map.of("totalTests",0,"passed",0,"failed",0,"blocked",0),
                "suites",List.of(),"incompleteModules",List.of(),"failures",List.of());
    }

    @PostMapping(value="/publish", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> publish(@RequestBody Map<String,Object> dashboard) {
        String html=HtmlReportBuilder.build(dashboard);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .header("Content-Disposition","attachment; filename=gct-certification-dashboard.html")
                .body(html.getBytes(StandardCharsets.UTF_8));
    }

    private ParsedReport parse(MultipartFile file) {
        String suite=detectSuite(file.getOriginalFilename());
        if (suite==null) return ParsedReport.unknown();

        List<String> entries=new ArrayList<>();
        String fingerprint="Not detected", patch="Not detected";
        try(ZipInputStream zis=new ZipInputStream(file.getInputStream())) {
            ZipEntry e;
            while((e=zis.getNextEntry())!=null) {
                if(e.isDirectory()) continue;
                String name=e.getName();
                if(entries.size()<3000) entries.add(name);
                if(name.endsWith(".xml")||name.endsWith(".txt")||name.endsWith(".json")) {
                    String text=new String(zis.readAllBytes(),StandardCharsets.UTF_8);
                    String f=match(text,"(?:buildFingerprint|build_fingerprint|fingerprint)\\s*[=:]\\s*[\\\"']?([^\\\"'\\s,<]+)");
                    String p=match(text,"(?:securityPatch|security_patch|ro.build.version.security_patch)\\s*[=:]\\s*[\\\"']?([^\\\"'\\s,<]+)");
                    if(!"Not detected".equals(f)) fingerprint=f;
                    if(!"Not detected".equals(p)) patch=p;
                }
            }
        } catch(Exception ignored) {}

        int modules=countModules(entries);
        if(modules==0) modules=Math.max(1,(int)entries.stream().map(this::moduleFromPath).filter(Objects::nonNull).distinct().count());
        int incompleteModules=(int)entries.stream().filter(e->e.toLowerCase(Locale.ROOT).contains("incomplete")).count();
        int failed=(int)entries.stream().filter(e->{
            String x=e.toLowerCase(Locale.ROOT); return x.contains("failed")||x.contains("failure");
        }).count();
        int testCases=(int)entries.stream().filter(e->e.endsWith(".xml")||e.endsWith(".json")).count();
        int completed=Math.max(0,modules-incompleteModules);
        String status=completed>=modules?"COMPLETED":"INCOMPLETE";

        ParsedReport p=new ParsedReport(suite,modules,completed,testCases,Math.max(0,testCases-failed),failed,status,fingerprint,patch);
        if("INCOMPLETE".equals(status))
            p.incomplete.add(Map.of("suite",suite,"module",suite+" report","reason","Incomplete result markers detected"));
        if(failed>0)
            p.failures.add(Map.of("suite",suite,"module",suite+" report","testCase","Detected failure","details",failed+" failure/failure-marker entries detected"));
        return p;
    }

    private static String detectSuite(String name) {
        if(name==null) return null;
        String n=name.toLowerCase(Locale.ROOT).replace("_","-").replace(" ","-").replace(".zip","");
        if(n.contains("cts-on-gsi")||n.contains("ctsongsi")) return "CTS-on-GSI";
        for(String s:SUPPORTED) {
            String key=s.toLowerCase(Locale.ROOT).replace("-","");
            if(n.replace("-","").contains(key)) return s;
        }
        return null;
    }

    private int countModules(List<String> entries) {
        return (int)entries.stream().filter(e->e.endsWith(".xml") &&
                (e.contains("/module/")||e.toLowerCase(Locale.ROOT).contains("test_result")||e.toLowerCase(Locale.ROOT).contains("/results/"))).count();
    }
    private String moduleFromPath(String p) {
        String[] a=p.split("/"); return a.length>1?a[a.length-2]:null;
    }
    private static String match(String text,String regex) {
        var m=java.util.regex.Pattern.compile(regex,java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find()?m.group(1):"Not detected";
    }
    private static int n(Object v){return v instanceof Number x?x.intValue():0;}

    private static final class ParsedReport {
        final String suite,status,fingerprint,patch; final int modules,completedModules,testCases,passed,failed;
        final List<Map<String,Object>> incomplete=new ArrayList<>(), failures=new ArrayList<>();
        ParsedReport(String s,int m,int c,int t,int p,int f,String st,String fp,String sp){suite=s;modules=m;completedModules=c;testCases=t;passed=p;failed=f;status=st;fingerprint=fp;patch=sp;}
        Map<String,Object> toMap(){return Map.of("name",suite,"modules",modules,"completedModules",completedModules,"testCases",testCases,"passed",passed,"failed",failed,"status",status);}
        static ParsedReport unknown(){return new ParsedReport(null,0,0,0,0,0,"UNSUPPORTED","Not detected","Not detected");}
    }
}
