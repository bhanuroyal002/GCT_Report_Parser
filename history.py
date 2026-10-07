import json,sqlite3
from pathlib import Path
from datetime import datetime,timezone

class HistoryStore:
    MAX_RUNS=20
    def __init__(self,path):
        self.path=Path(path);self.path.parent.mkdir(parents=True,exist_ok=True);self.init()
    def conn(self):
        c=sqlite3.connect(self.path);c.row_factory=sqlite3.Row;return c
    def init(self):
        with self.conn() as c:
            c.execute("""CREATE TABLE IF NOT EXISTS analysis_history(
              id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT UNIQUE NOT NULL,analyzed_at TEXT NOT NULL,
              status TEXT NOT NULL,build_fingerprint TEXT,android_version TEXT,security_patch TEXT,
              suite_count INTEGER NOT NULL,total_tests INTEGER NOT NULL,passed INTEGER NOT NULL,failed INTEGER NOT NULL,
              dashboard_json TEXT NOT NULL)""")
            c.execute("CREATE INDEX IF NOT EXISTS idx_history_time ON analysis_history(analyzed_at DESC)")
    def save(self,d):
        o=d.get("overall",{});bad=bool(o.get("fingerprintMismatch"));failed=int(o.get("failed",0));inc=d.get("incompleteModules",[])
        status="BUILD MISMATCH" if bad else "READY FOR REVIEW" if failed==0 and not inc else "ATTENTION REQUIRED"
        rid=datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")[:-3]
        when=d.get("generatedAt") or datetime.now(timezone.utc).isoformat()
        with self.conn() as c:
            c.execute("""INSERT INTO analysis_history(run_id,analyzed_at,status,build_fingerprint,android_version,security_patch,
              suite_count,total_tests,passed,failed,dashboard_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
              (rid,when,status,d.get("buildFingerprint",""),d.get("androidVersion",""),d.get("securityPatch",""),
               len(d.get("suites",[])),int(o.get("totalTests",0)),int(o.get("passed",0)),failed,json.dumps(d)))
            c.execute("""DELETE FROM analysis_history WHERE id NOT IN
              (SELECT id FROM analysis_history ORDER BY analyzed_at DESC,id DESC LIMIT ?)""",(self.MAX_RUNS,))
        d["runId"]=rid;return d
    def list_runs(self):
        with self.conn() as c:
            rows=c.execute("""SELECT run_id,analyzed_at,status,build_fingerprint,android_version,security_patch,
              suite_count,total_tests,passed,failed FROM analysis_history ORDER BY analyzed_at DESC,id DESC LIMIT ?""",(self.MAX_RUNS,)).fetchall()
        return [{"runId":r["run_id"],"analyzedAt":r["analyzed_at"],"status":r["status"],"buildFingerprint":r["build_fingerprint"],
          "androidVersion":r["android_version"],"securityPatch":r["security_patch"],"suiteCount":r["suite_count"],
          "totalTests":r["total_tests"],"passed":r["passed"],"failed":r["failed"]} for r in rows]
    def get_run(self,rid):
        with self.conn() as c:
            r=c.execute("SELECT dashboard_json FROM analysis_history WHERE run_id=?",(rid,)).fetchone()
        return json.loads(r["dashboard_json"]) if r else None
