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
  const o=d.overall||{},s=d.suites||[],inc=d.incompleteModules||[],fail=d.failures||[];
  const ready=s.length&&Number(o.failed||0)===0&&inc.length===0;
  $("readiness").textContent=ready?"READY FOR REVIEW":"ATTENTION REQUIRED";
  $("readiness").className="readiness-chip "+(ready?"ready":"attention");
  const rate=((Number(o.passed||0)/Math.max(1,Number(o.totalTests||0)))*100).toFixed(1);
  $("stats").innerHTML=[
    ["TOTAL TESTS",format(o.totalTests),"Across uploaded suites"],
    ["PASSED",format(o.passed),rate+"% pass rate"],
    ["FAILED",format(o.failed),"Actual Tradefed failures"],
    ["WARNINGS",format(o.warnings),"Reported warnings"]
  ].map(x=>`<div class="stat"><span>${x[0]}</span><strong>${x[1]}</strong><small>${x[2]}</small></div>`).join("");

  $("suiteGrid").innerHTML=s.map(x=>{
    const p=x.modules?Math.round(x.completedModules/x.modules*100):0;
    return `<article class="suite">
      <div class="suite-top"><h3>${esc(x.name)}</h3><span class="pill ${x.status==="COMPLETED"?"ok":"warn"}">${esc(x.status)}</span></div>
      <div class="suite-number">${format(x.completedModules)} / ${format(x.modules)} modules</div>
      <div class="progress"><i style="width:${p}%"></i></div>
      <div class="suite-meta"><span>${format(x.testCases)} test cases</span><span>${p}% complete</span></div>
      <div class="suite-fails">${format(x.failed)} failed · ${format(x.warnings)} warnings</div>
    </article>`
  }).join("");

  $("suiteDetails").innerHTML=s.map(x=>{
    const details=x.moduleDetails||[];
    return `<section class="detail-card">
      <div class="detail-head">
        <div><span class="section-kicker">${esc(x.name)} · ${esc(x.plan||"plan")}</span>
        <h3>${esc(x.version||"")} / ${esc(x.buildNumber||"")}</h3></div>
        <span class="detail-result">${format(x.passed)} passed · ${format(x.failed)} failed</span>
      </div>
      <div class="summary-grid">
        <div><span>HOST</span><strong>${esc(x.hostInfo||"Not detected")}</strong></div>
        <div><span>START / END</span><strong>${esc(x.start||"Not detected")} / ${esc(x.end||"Not detected")}</strong></div>
        <div><span>FINGERPRINT</span><strong>${esc(x.fingerprint||"Not detected")}</strong></div>
        <div><span>SECURITY PATCH</span><strong>${esc(x.securityPatch||"Not detected")}</strong></div>
        <div><span>RELEASE (SDK)</span><strong>${esc(x.release||"Not detected")} (${esc(x.sdk||"")})</strong></div>
        <div><span>ABIs</span><strong>${esc(x.abis||"Not detected")}</strong></div>
      </div>
      <div class="module-table-wrap">
        <table class="module-table"><thead><tr><th>Module</th><th>Passed</th><th>Failed</th><th>Total Tests</th><th>Done</th></tr></thead>
        <tbody>${details.map(m=>`<tr class="${m.done?"":"incomplete"}"><td>${esc((m.abi?m.abi+" ":"")+m.name)}</td><td>${format(m.passed)}</td><td>${format(m.failed)}</td><td>${format(m.totalTests)}</td><td>${m.done?"true":"false"}</td></tr>`).join("")}</tbody></table>
      </div>
    </section>`
  }).join("");

  $("incompleteCount").textContent=inc.length;
  $("failureCount").textContent=fail.length;
  $("incompleteList").innerHTML=inc.length?inc.map(x=>`<div class="issue-row"><strong>${esc(x.suite)} · ${esc(x.module)}</strong><span>${esc(x.reason)}</span></div>`).join(""):"<div class='empty'>No incomplete modules.</div>";
  $("failureList").innerHTML=fail.length?fail.map(x=>`<div class="issue-row"><strong>${esc(x.suite)} · ${esc(x.module)} · ${esc(x.testCase)}</strong><span>${esc(x.details)}</span></div>`).join(""):"<div class='empty'>No failures detected.</div>";
  $("publishBtn").disabled=false;
}
$("reportFiles").addEventListener("change",e=>{state.files=[...e.target.files];renderFiles()});
$("clearBtn").addEventListener("click",()=>{
  state.files=[];state.data=null;$("reportFiles").value="";renderFiles();$("publishBtn").disabled=true;
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
$("publishBtn").addEventListener("click",async()=>{
  if(!state.data)return;
  const b=$("publishBtn");b.disabled=true;b.textContent="Preparing dashboard…";
  try{
    const r=await fetch("/api/publish",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(state.data)});
    if(!r.ok)throw new Error("Publish failed");
    const blob=await r.blob(),url=URL.createObjectURL(blob),a=document.createElement("a");
    a.href=url;a.download="gct-certification-dashboard.html";a.click();URL.revokeObjectURL(url);
  }catch(e){alert(e.message)}finally{b.disabled=false;b.textContent="Download Dashboard HTML ↓"}
});
renderFiles();