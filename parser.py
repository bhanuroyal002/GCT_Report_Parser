import io,os,zipfile,hashlib
from collections import OrderedDict
from datetime import datetime,timezone
from xml.etree import ElementTree as ET

MAX_DEPTH=int(os.getenv("MAX_ZIP_DEPTH","5"))

def first(*v):
    for x in v:
        if x is not None and str(x).strip(): return str(x).strip()
    return ""
def num(x):
    try:return int(x)
    except:return 0
def suite(name,plan):
    x=first(plan,name).upper().strip().replace("_","-").replace(" ","-")
    return "CTS-VERIFIER" if x in {"VERIFIER","CTSVERIFIER","CTS-VERIFIER"} else x
def fingerprint(x):
    x=str(x or "").strip();return "" if not x or x.lower()=="not detected" else x
def parse_xml(data,source):
    root=ET.fromstring(data)
    if root.tag.rsplit("}",1)[-1]!="Result": return None
    s=suite(root.get("suite_name"),root.get("suite_plan"))
    if not s:return None
    sm=root.find("Summary")\n    b=root.find("Build")\n    if sm is None: sm=ET.Element("Summary")\n    if b is None: b=ET.Element("Build")
    r={"suite":s,"plan":first(root.get("suite_plan"),s),"version":first(root.get("suite_version"),root.get("version")),
       "buildNumber":first(root.get("suite_build_number"),root.get("build_number")),
       "fingerprint":first(b.get("build_fingerprint"),"Not detected"),"securityPatch":first(b.get("build_version_security_patch"),"Not detected"),
       "release":first(b.get("build_version_release"),root.get("android_version"),"Not detected"),
       "sdk":first(b.get("build_version_sdk"),"Not detected"),"abis":first(b.get("build_abis"),"Not detected"),
       "buildId":first(b.get("build_id"),"Not detected"),"passed":num(sm.get("pass")),"failed":num(sm.get("failed")),
       "assumptionFailures":num(sm.get("assumption_failure"))+num(sm.get("assumption-failure")),
       "ignored":num(sm.get("ignored"))+num(sm.get("not_executed")),"warnings":num(sm.get("warning")),
       "modules":{},"tests":[],"source":source}
    for m in root.findall(".//Module"):
        mn=first(m.get("name"),"Unknown Module");abi=first(m.get("abi"));key=(abi+"|"+mn).lower()
        r["modules"][key]={"name":mn,"abi":abi,"done":m.get("done","").lower()=="true"}
        for tc in m.findall(".//TestCase"):
            cn=first(tc.get("name"),"UnknownTestCase")
            for t in tc.findall("Test"):
                result=str(t.get("result") or "").lower().replace("-","_");tn=first(t.get("name"),"unknown")
                k=hashlib.sha256("|".join([s,abi,mn,cn,tn]).encode()).hexdigest()
                f=t.find("Failure");details=first(f.get("message") if f is not None else "",f.get("trace") if f is not None else "")
                r["tests"].append({"key":k,"suite":s,"module":mn,"abi":abi,"testCase":cn,"name":tn,"result":result,"details":details})
    return r

def scan_bytes(data,source,depth,seen):
    if depth>MAX_DEPTH:return [],[f"{source}: maximum nested ZIP depth reached."]
    out=[];errors=[]
    try:z=zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile as e:return [],[f"{source}: invalid ZIP ({e})"]
    with z:
        for i in z.infolist():
            if i.is_dir():continue
            payload=z.read(i);name=i.filename
            if name.lower().endswith(".zip"):
                h=hashlib.sha256(payload).hexdigest()
                if h in seen:continue
                seen.add(h);a,b=scan_bytes(payload,f"{source}!{name}",depth+1,seen);out+=a;errors+=b
            elif name.lower().endswith("test_result.xml"):
                try:
                    x=parse_xml(payload,f"{source}!{name}")
                    if x:out.append(x)
                except (ET.ParseError,UnicodeError) as e:errors.append(f"{name}: XML parse error ({e})")
    return out,errors

def scan(path,name,seen):
    try:data=open(path,"rb").read();return scan_bytes(data,name,0,seen)
    except OSError as e:return [],[f"{name}: {e}"]

def merge(reports):
    tests=OrderedDict();modules={}
    for r in reports:
        for k,m in r["modules"].items():
            if k not in modules or (m["done"] and not modules[k]["done"]):modules[k]=dict(m)
        for t in r["tests"]:
            if t["key"] not in tests or tests[t["key"]]["result"]!="pass":tests[t["key"]]=t
    counts={};fails=[]
    for t in tests.values():
        k=(t["abi"]+"|"+t["module"]).lower();c=counts.setdefault(k,{"passed":0,"failed":0,"assumptionFailures":0,"ignored":0,"totalTests":0});c["totalTests"]+=1
        if t["result"]=="pass":c["passed"]+=1
        elif t["result"]=="fail":
            c["failed"]+=1;fails.append({"suite":t["suite"],"module":(t["abi"]+" "+t["module"]).strip(),"testCase":t["testCase"]+"#"+t["name"],"details":t["details"] or "Test failed"})
        elif t["result"]=="assumption_failure":c["assumptionFailures"]+=1
        elif t["result"] in {"ignored","not_executed"}:c["ignored"]+=1
    mods=[];inc=[]
    for k,m in modules.items():
        c=counts.get(k,{"passed":0,"failed":0,"assumptionFailures":0,"ignored":0,"totalTests":0});row={**m,**c};mods.append(row)
        if not row["done"]:inc.append({"suite":reports[0]["suite"],"module":(row["abi"]+" "+row["name"]).strip(),"failed":row["failed"],"reason":"Module is marked done=false in Tradefed result"})
    if tests:
        p=sum(1 for x in tests.values() if x["result"]=="pass");f=sum(1 for x in tests.values() if x["result"]=="fail");a=sum(1 for x in tests.values() if x["result"]=="assumption_failure");ig=sum(1 for x in tests.values() if x["result"] in {"ignored","not_executed"})
    else:
        p=sum(r["passed"] for r in reports);f=sum(r["failed"] for r in reports);a=sum(r["assumptionFailures"] for r in reports);ig=sum(r["ignored"] for r in reports)
    return {"name":reports[0]["suite"],"plan":first(*(r["plan"] for r in reports)),"version":first(*(r["version"] for r in reports)),
      "buildNumber":first(*(r["buildNumber"] for r in reports)),"passed":p,"failed":f,"assumptionFailures":a,"ignored":ig,
      "warnings":max((r["warnings"] for r in reports),default=0),"modules":len(mods),"completedModules":sum(x["done"] for x in mods),
      "testCases":p+f+a+ig,"status":"COMPLETED" if mods and all(x["done"] for x in mods) else "INCOMPLETE",
      "fingerprint":first(*(r["fingerprint"] for r in reports)),"securityPatch":first(*(r["securityPatch"] for r in reports)),
      "release":first(*(r["release"] for r in reports)),"sdk":first(*(r["sdk"] for r in reports)),"abis":first(*(r["abis"] for r in reports)),
      "buildId":first(*(r["buildId"] for r in reports)),"moduleDetails":mods,"_failures":fails,"_incomplete":inc}

def analyze_reports(files):
    allr=[];diag=[];seen=set()
    for name,path in files:
        rs,es=scan(path,name,seen);allr+=rs;diag.append({"file":name,"xmlFilesFound":len(rs),"recognizedReports":len(rs),"errors":es})
    if not allr:raise ValueError("No recognized Tradefed test_result.xml reports were found in the uploaded ZIP files.")
    by=OrderedDict();known=set();unknown=False
    for r in allr:
        k=fingerprint(r["fingerprint"]);known.add(k) if k else None;unknown=unknown or not k;by.setdefault(k or "not detected",[]).append(r)
    mismatch=len(known)>1 or (unknown and bool(known))
    builds=[]
    for _,rs in by.items():
        x=rs[0];builds.append({"fingerprint":x["fingerprint"],"securityPatch":x["securityPatch"],"androidVersion":x["release"],"buildId":x["buildId"],"sdk":x["sdk"],"abis":x["abis"],"suites":sorted({r["suite"] for r in rs})})
    suites=[];inc=[];fails=[]
    if not mismatch:
        bs=OrderedDict()
        for rs in by.values():
            for r in rs:bs.setdefault(r["suite"],[]).append(r)
        for rs in bs.values():
            x=merge(rs);inc+=x.pop("_incomplete");fails+=x.pop("_failures");suites.append(x)
    o={"totalTests":sum(x["testCases"] for x in suites),"passed":sum(x["passed"] for x in suites),"failed":sum(x["failed"] for x in suites),
       "assumptionFailures":sum(x["assumptionFailures"] for x in suites),"ignored":sum(x["ignored"] for x in suites),"fingerprintMismatch":mismatch}
    b=builds[0] if len(builds)==1 else {}
    return {"generatedAt":datetime.now(timezone.utc).isoformat(),"buildFingerprint":b.get("fingerprint","Not detected") if not mismatch else "",
      "androidVersion":b.get("androidVersion","Not detected") if not mismatch else "","securityPatch":b.get("securityPatch","Not detected") if not mismatch else "",
      "builds":builds,"suites":suites,"incompleteModules":inc,"failures":fails,"overall":o,"xmlReportsFound":len(allr),"recognizedReports":len(allr),"reportDiagnostics":diag}
