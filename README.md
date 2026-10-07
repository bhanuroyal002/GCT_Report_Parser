# GCT Report Parser

A clean Python web application for parsing Android certification **Tradefed result ZIPs** and generating a unified release-readiness dashboard.

The tool is designed for Android automation and certification workflows where CTS, GTS, TVTS, STS, VTS, CTS-on-GSI and CTS Verifier reports need to be reviewed together.

## What it does

- Upload one or more certification ZIP reports.
- Recursively searches nested ZIP files for `test_result.xml`.
- Detects suite and build information from Tradefed result data.
- Groups reports by build fingerprint.
- Stops aggregation when different builds are uploaded together.
- Merges split reports and reruns.
- **Rerun rule:** if the same testcase passes in any execution, the final state is PASS.
- Shows build fingerprint, Android version and security patch.
- Shows module completion and testcase counts.
- Highlights incomplete modules.
- Lists final failed testcases and failure details.
- Generates a standalone HTML dashboard.
- Stores the latest 20 successful analysis runs in a local SQLite database.

## Technology

This repository is now **100% Python for the application layer**.

- Python 3.10+
- Flask 3.1+
- SQLite — built into Python
- Python standard library for ZIP/XML/JSON/database processing
- HTML/CSS/JavaScript frontend

### Removed

The project no longer requires:

- Java
- Maven
- Spring Boot
- PostgreSQL
- Docker
- Docker Compose
- Node.js
- Python Android/ADB tooling

**Maven is not required.**

## Requirements

Install:

- Python 3.10 or newer
- Git
- A modern browser

No separate database server is required. SQLite is created automatically under:

```text
data/gct_report_parser.db
```

## Installation

Clone the repository:

```bash
git clone https://github.com/bhanuroyal002/GCT_Report_Parser.git
cd GCT_Report_Parser
```

Create a Python virtual environment:

```bash
python3 -m venv .venv
source .venv/bin/activate
```

Install the application dependencies:

```bash
pip install -r requirements.txt
```

Start the application:

```bash
python app.py
```

Open:

```text
http://127.0.0.1:8080
```

Flask's development server is suitable for local development; do not use it as the production server. citeturn0search1turn0search2

## Team/server usage

For a Linux server where teammates need to access the application over the network, configure:

```bash
export HOST=0.0.0.0
export PORT=8080
python app.py
```

Then teammates can open:

```text
http://<server-ip>:8080
```

For a more stable server process, use Gunicorn:

```bash
gunicorn --workers 2 --bind 0.0.0.0:8080 app:app
```

The Flask application can also be placed behind an existing reverse proxy or tunnel.

## Optional configuration

Copy:

```bash
cp .env.example .env
```

The application reads configuration from environment variables:

| Variable | Default | Purpose |
|---|---|---|
| HOST | 127.0.0.1 | Listen address |
| PORT | 8080 | Web port |
| DEBUG | false | Flask debug mode |
| DATABASE_PATH | data/gct_report_parser.db | SQLite database |
| MAX_FILE_SIZE_MB | 500 | Maximum individual ZIP |
| MAX_REQUEST_SIZE_MB | 1024 | Maximum upload request |
| MAX_ZIP_DEPTH | 5 | Nested ZIP recursion limit |

The application does not require the `.env` file; environment variables can be exported directly.

## How to use

### Step 1 — Open the application

Open:

```text
http://127.0.0.1:8080
```

### Step 2 — Upload reports

Select one or more ZIP files.

Supported certification/report types include:

- CTS
- GTS
- TVTS
- STS
- VTS
- CTS-on-GSI
- CTS Verifier

The ZIP filename does not determine the suite. The parser reads the Tradefed XML.

### Step 3 — Analyze

Click:

```text
Analyze Reports →
```

The application:

1. Reads each ZIP.
2. Searches nested archives.
3. Finds `test_result.xml`.
4. Extracts build metadata.
5. Parses modules and testcases.
6. Reconciles reruns.
7. Builds the dashboard.
8. Saves the successful analysis to SQLite.

### Step 4 — Check build identity

The dashboard shows:

- Build fingerprint
- Android version
- Security patch
- Build ID
- SDK
- Architecture

Reports with different build fingerprints are not merged.

### Step 5 — Review results

The dashboard shows:

- Suite
- Total modules
- Completed modules
- Incomplete modules
- Passed tests
- Failed tests
- Assumption failures
- Ignored tests
- Total tests

### Step 6 — Review failures

Failed testcases are listed with:

- Suite
- Module
- Testcase
- Failure details

### Step 7 — Publish

Use:

- **View Dashboard**
- **Download Dashboard HTML**

The generated dashboard is standalone HTML and can be shared independently.

## Rerun reconciliation

For repeated executions of the same testcase:

```text
PASS + FAIL  -> PASS
FAIL + PASS  -> PASS
PASS + PASS  -> PASS
FAIL + FAIL  -> FAIL
```

This follows the existing certification workflow where reruns are used to recover failed testcases.

## Build mismatch behavior

If reports contain different fingerprints, the application displays:

```text
BUILD MISMATCH
```

Testcase/module aggregation is intentionally stopped.

This prevents results from different device builds from being silently combined.

## History

The application stores the latest **20 successful analysis runs** in SQLite.

The history contains:

- Run ID
- Analysis timestamp
- Status
- Build fingerprint
- Android version
- Security patch
- Suite count
- Test count
- Passed count
- Failed count
- Complete dashboard JSON

Uploaded ZIP files are **not** stored permanently.

The database is created automatically:

```text
data/gct_report_parser.db
```

Back up this file if historical analysis data needs to be preserved.

## Project structure

```text
GCT_Report_Parser/
├── app.py
├── parser.py
├── history.py
├── report_builder.py
├── requirements.txt
├── .env.example
├── .gitignore
├── templates/
│   └── index.html
├── static/
│   ├── app.js
│   └── style.css
└── data/
    └── gct_report_parser.db   # created automatically, ignored by Git
```

## Development

Activate the virtual environment:

```bash
source .venv/bin/activate
```

Start locally:

```python
python app.py
```

Check syntax:

```bash
python -m py_compile app.py parser.py history.py report_builder.py
```

## Production

Do not run Flask's development server for a production deployment. Flask's documentation recommends using a production WSGI server such as Gunicorn instead. citeturn0search1turn0search2

Example:

```bash
gunicorn --workers 2 --bind 0.0.0.0:8080 app:app
```

For an internal team server, the recommended architecture is:

```text
Teammate Browser
       |
       v
Reverse Proxy / Cloudflare Tunnel / LAN
       |
       v
Gunicorn :8080
       |
       v
Flask Application
       |
       +---- parser.py
       |
       +---- history.py
       |
       +---- SQLite
```

## License

No license has been specified for this repository yet.
