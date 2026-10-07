import os,tempfile
from pathlib import Path
from flask import Flask,jsonify,render_template,request,send_file
from history import HistoryStore
from parser import analyze_reports
from report_builder import build_dashboard_html

BASE=Path(__file__).resolve().parent
DB=os.getenv("DATABASE_PATH",str(BASE/"data"/"gct_report_parser.db"))
MAX_FILE=int(os.getenv("MAX_FILE_SIZE_MB","500"))*1024*1024
MAX_REQUEST=int(os.getenv("MAX_REQUEST_SIZE_MB","1024"))*1024*1024
app=Flask(__name__,template_folder="templates",static_folder="static")
app.config["MAX_CONTENT_LENGTH"]=MAX_REQUEST
history=HistoryStore(DB)

@app.get("/")
def index(): return render_template("index.html")

@app.get("/api/history")
def history_list(): return jsonify({"runs":history.list_runs(),"maxRuns":history.MAX_RUNS})

@app.get("/api/history/<run_id>")
def history_run(run_id):
    data=history.get_run(run_id)
    return (jsonify(data),200) if data else (jsonify({"error":"Historical run not found."}),404)

@app.post("/api/analyze")
def analyze():
    files=request.files.getlist("files")
    if not files: return jsonify({"error":"Upload at least one report ZIP file."}),400
    paths=[]
    try:
        for f in files:
            if not f.filename: continue
            if Path(f.filename).suffix.lower()!=".zip":
                return jsonify({"error":f"Only ZIP files are supported: {f.filename}"}),400
            fd,p=tempfile.mkstemp(prefix="gct-report-",suffix=".zip");os.close(fd);f.save(p)
            if os.path.getsize(p)>MAX_FILE:
                return jsonify({"error":f"{f.filename} exceeds the {MAX_FILE//1048576} MB file limit."}),413
            paths.append((f.filename,p))
        data=analyze_reports(paths);history.save(data);return jsonify(data)
    except ValueError as e: return jsonify({"error":str(e)}),400
    except Exception as e:
        app.logger.exception("analysis failed");return jsonify({"error":f"Analysis failed: {e}"}),500
    finally:
        for _,p in paths:
            try: os.unlink(p)
            except OSError: pass

@app.post("/api/publish")
def publish():
    data=request.get_json(silent=True)
    if not isinstance(data,dict): return jsonify({"error":"Dashboard data is required."}),400
    fd,p=tempfile.mkstemp(prefix="gct-dashboard-",suffix=".html");os.close(fd)
    Path(p).write_text(build_dashboard_html(data),encoding="utf-8")
    return send_file(p,mimetype="text/html",download_name="gct-certification-dashboard.html",max_age=0)

@app.errorhandler(413)
def too_large(_): return jsonify({"error":"Upload is too large. The total request limit is 1 GB."}),413

if __name__=="__main__":
    app.run(host=os.getenv("HOST","127.0.0.1"),port=int(os.getenv("PORT","8080")),debug=os.getenv("DEBUG","false").lower()=="true")
