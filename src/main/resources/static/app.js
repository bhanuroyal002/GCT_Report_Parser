const state={data:null,files:[]};
const $=id=>document.getElementById(id);
const format=n=>Number(n||0).toLocaleString("en-IN");
const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
function renderFiles(){
  $("selectedFiles").innerHTML=state.files.map(f=>`<span class="file-chip">${esc(f.name)} <small>(${Math.round(f.size/1024)} KB)</small></span>`).join("");
  $("analyzeBtn").disabled=!state.files.length;
  $("clearBtn").disabled=!state.files.length;
}
function render(){
  const d=state.data;if(!d)return;
  $("buildFingerprint").textContent=d.buildFingerprint||"Not detected";
  $("securityPatch").textContent=d.securityPatch||"Not detected";
  $("androidVersion").textContent=d.androidVersion||"Not detected";
  const o=d.overall||{},s=d.suites||[],inc=d.incompleteModules||[],fail=d.failures||[];
  const fingerprintMismatch=Boolean(o.fingerprintMismatch);
  $("buildMismatch").innerHTML=fingerprintMismatch?(`
    <div class="mismatch-panel">
      <div class="panel-title"><div><span class="section-kicker">BUILD MISMATCH</span><h3>Test suite build fingerprints</h3></div><span class="count-badge danger">${format(s.length)} builds</span></div>
      <p>Reports with different build fingerprints were kept separate and were not merged.</p>
      <div class="fingerprint-table">
        ${s.map(x=>`<div class="fingerprint-row"><strong>${esc(x.name)}</strong><span>${esc(x.fingerprint||"Not detected")}</span></div>`).join("")}
      </div>
    </div>`):"";

  const ready=s.length&&Number(o.failed||0)===0&&inc.length===0&&!fingerprintMismatch;
  $("readiness").textContent=fingerprintMismatch?"BUILD MISMATCH":(ready?"READY FOR REVIEW":"ATTENTION REQUIRED");
  $("readiness").className="readiness-chip "+(ready?"ready":"attention");
  const rate=((Number(o.passed||0)/Math.max(1,Number(o.totalTests||0)))*100).toFixed(1);
  $("stats").innerHTML=[
    ["PASSED",format(o.passed),"Passed test cases"],
    ["FAILED",format(o.failed),"Failed test cases"],
    ["ASSUMPTION FAILURE",format(o.assumptionFailures),"Assumption failures"],
    ["IGNORED",format(o.ignored),"Ignored test cases"],
    ["TOTAL TESTS",format(o.totalTests),rate+"% pass rate"]
  ].map(x=>`<div class="stat"><span>${x[0]}</span><strong>${x[1]}</strong><small>${x[2]}</small></div>`).join("");

  $("suiteGrid").innerHTML=s.map(x=>{
    const p=x.modules?Math.round(x.completedModules/x.modules*100):0;
    return `<article class="suite">
      <div class="suite-top"><h3>${esc(x.name)}</h3><span class="pill ${x.status==="COMPLETED"?"ok":"warn"}">${esc(x.status)}</span></div>
      <div class="suite-number">${format(x.completedModules)} / ${format(x.modules)} modules</div>
      <div class="progress"><i style="width:${p}%"></i></div>
      <div class="suite-meta"><span>${p}% complete</span><span>${format(x.testCases)} total tests</span></div>
      <div class="suite-fails">${format(x.passed)} passed · ${format(x.failed)} failed · ${format(x.assumptionFailures)} assumption failure · ${format(x.ignored)} ignored</div>
    </article>`
  }).join("");

  $("incompleteCount").textContent=inc.length;
  $("failureCount").textContent=fail.length;
  $("incompleteList").innerHTML=inc.length?inc.map(x=>`<div class="issue-row"><strong>${esc(x.suite)} · ${esc(x.module)}</strong><span>${esc(x.reason)}</span></div>`).join(""):"<div class='empty'>No incomplete modules.</div>";
  $("failureList").innerHTML=fail.length?fail.map(x=>`<div class="issue-row"><strong>${esc(x.suite)} · ${esc(x.module)} · ${esc(x.testCase)}</strong><span>${esc(x.details)}</span></div>`).join(""):"<div class='empty'>No failures detected.</div>";
  $("publishBtn").disabled=false;$("viewBtn").disabled=false;
}
$("reportFiles").addEventListener("change",e=>{state.files=[...e.target.files];renderFiles()});
$("clearBtn").addEventListener("click",()=>{
  state.files=[];state.data=null;$("reportFiles").value="";renderFiles();$("publishBtn").disabled=true;$("viewBtn").disabled=true;
  $("statusMessage").textContent="";$("stats").innerHTML="";$("suiteGrid").innerHTML="";$("suiteDetails").innerHTML="";
  $("incompleteList").innerHTML="";$("failureList").innerHTML="";
  $("readiness").textContent="WAITING FOR REPORTS";$("readiness").className="readiness-chip";
});
$("analyzeBtn").addEventListener("click",async()=>{
  const b=$("analyzeBtn");b.disabled=true;b.textContent="Analyzing reports…";
  $("statusMessage").textContent="Uploading and parsing Tradefed result XML…";
  const fd=new FormData();state.files.forEach(f=>fd.append("files",f));
  try{
    const r=await fetch("/api/analyze",{method:"POST",body:fd});
    const d=await r.json();
    if(!r.ok)throw new Error(d.error||"Analysis failed");
    state.data=d;render();
    $("statusMessage").textContent=`Analysis complete: ${(d.suites||[]).length} recognized suite(s).`;
  }catch(e){
    $("statusMessage").textContent=e.message;
    $("readiness").textContent="ANALYSIS FAILED";
    $("readiness").className="readiness-chip attention";
  }finally{b.disabled=!state.files.length;b.textContent="Analyze Reports →"}
});
async function getDashboardUrl(){
  if(!state.data)throw new Error("Analyze reports first");
  const r=await fetch("/api/publish",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(state.data)});
  if(!r.ok)throw new Error("Publish failed");
  return URL.createObjectURL(await r.blob());
}
$("viewBtn").addEventListener("click",async()=>{
  const b=$("viewBtn");b.disabled=true;b.textContent="Opening dashboard…";
  try{
    const url=await getDashboardUrl();
    const win=window.open(url,"_blank");
    if(!win)throw new Error("Popup blocked. Please allow popups for this site.");
    setTimeout(()=>URL.revokeObjectURL(url),60000);
  }catch(e){alert(e.message)}finally{b.disabled=false;b.textContent="View Dashboard ↗"}
});
$("publishBtn").addEventListener("click",async()=>{
  const b=$("publishBtn");b.disabled=true;b.textContent="Preparing dashboard…";
  try{
    const url=await getDashboardUrl(),a=document.createElement("a");
    a.href=url;a.download="gct-certification-dashboard.html";a.click();
    setTimeout(()=>URL.revokeObjectURL(url),1000);
  }catch(e){alert(e.message)}finally{b.disabled=false;b.textContent="Download Dashboard HTML ↓"}
});
renderFiles();