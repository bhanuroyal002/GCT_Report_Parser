# GCT Report Parser

A Spring Boot web application for parsing Android certification **Tradefed result ZIPs** and generating a unified release-readiness dashboard.

The tool is intended for Android automation and certification workflows where multiple automation reports need to be reviewed together.

## Features

- Upload one or more certification ZIP reports in a single analysis.
- Recursively scans nested ZIP files for Tradefed `test_result.xml` files.
- Detects report information directly from the Tradefed result data rather than relying on ZIP filenames.
- Groups reports by build fingerprint so reports from different builds are not merged.
- Detects multiple build fingerprints and stops testcase/module aggregation when builds do not match.
- Merges split reports and reruns using testcase identity.
- **Rerun rule:** if the same testcase passes in any report, its final status is treated as **PASS**. If it never passes, the final observed failure/status is retained.
- Shows build fingerprint, Android version and security patch.
- Shows module completion and test-result counts.
- Highlights incomplete modules.
- Lists final failed test cases and failure details.
- Provides a stakeholder-facing dashboard that can be viewed or downloaded as standalone HTML.
- Stores the latest **20 successful analysis runs** in PostgreSQL and lets users view/download historical dashboards.
- Provides a Docker Compose deployment with PostgreSQL so the team can use the application without installing Java/Maven locally.
- Uses a streaming StAX XML parser to handle large Tradefed result files without loading the entire XML document into memory.

## Supported reports

The parser is designed for **automation and certification result archives** containing Tradefed `test_result.xml` files.

It supports processing certification automation reports, including **CTS Verifier** results.

The tool automatically analyzes the available report data and generates a consolidated dashboard without relying on hardcoded test-suite names.

## Requirements

### Required

| Requirement | Version / Details |
|---|---|
| Java | **17 or later** |
| Maven | **3.9 or later recommended** for source builds |
| PostgreSQL | **16+** for non-Docker local runs |
| Docker | **Docker Engine + Docker Compose** for recommended team deployment |
| Git | Required to clone/update the repository |
| Browser | Modern Chrome, Edge or Firefox |
| RAM | **8 GB minimum recommended**; 16 GB+ preferred for large reports |
| Disk space | Sufficient space for uploaded ZIPs, extracted/processed reports and Maven dependencies |

Node.js, Python and Android SDK are not required. PostgreSQL is used for shared analysis history. The recommended team deployment runs both the application and PostgreSQL in Docker, so team members do not need Java, Maven or PostgreSQL installed.

### Recommended environment

The application can run on:

- Ubuntu/Linux
- WSL2
- Windows/macOS/Linux with Java and Maven installed

## Recommended team deployment

For shared team usage, run the application and PostgreSQL with Docker Compose.

### Requirements on the server

Only Docker is required:

```bash
docker --version
docker compose version
```

### Start the team server

Clone the repository on an always-on Ubuntu/Linux or Windows server:

```bash
git clone https://github.com/bhanuroyal002/GCT_Report_Parser.git
cd GCT_Report_Parser
```

Build and start both containers:

```bash
docker compose up -d --build
```

Check the containers:

```bash
docker compose ps
```

Open from the server itself:

```text
http://localhost:8080
```

From other machines on the same network, use the server's IP address:

```text
http://<server-ip>:8080
```

The deployment contains:

```text
gct-report-parser
        |
        +---- PostgreSQL 16
                  |
                  +---- gct-postgres-data volume
```

The PostgreSQL volume keeps the shared analysis history when the containers are restarted or recreated.

### Stop the server

```bash
docker compose down
```

This stops the containers but keeps the PostgreSQL volume.

### Start again

```bash
docker compose up -d
```

### Important: do not use `docker compose down -v`

The `-v` option deletes the PostgreSQL volume and therefore deletes the stored analysis history.

### Shared 20-run history

Every successful analysis is stored in PostgreSQL. The application keeps only the newest 20 successful runs.

Each run contains the complete parsed dashboard data, so users can:

- View a previous dashboard.
- Download a previous dashboard as standalone HTML.
- Continue using the current analysis independently.

A failed HTTP request or analysis that produces no recognized Tradefed report is not stored as a successful history run.

### Database persistence

The PostgreSQL data is stored in the Docker named volume:

```text
gct-postgres-data
```

The application does not store uploaded ZIP files permanently. The ZIPs are processed for the current analysis and are not inserted into the database.

### Production note

For an internal company deployment, expose port 8080 only inside the company network or place the application behind the company's reverse proxy/HTTPS endpoint. The default Docker Compose credentials are intended for an internal demo/deployment and should be changed before a wider production rollout.
## Installation

### 1. Clone the repository

```bash
git clone https://github.com/bhanuroyal002/GCT_Report_Parser.git
cd GCT_Report_Parser
```

If the repository is already cloned:

```bash
git pull
```

### 2. Verify Java

```bash
java -version
```

Java 17 or newer is required.

### 3. Verify Maven

```bash
mvn -version
```

Maven 3.9+ is recommended.

### 4. Build the application

```bash
mvn clean package
```

This compiles the application and runs the automated tests.

For a faster local build when tests are not required:

```bash
mvn clean package -DskipTests
```

## Start the application

Run:

```bash
mvn spring-boot:run
```

The application starts on:

```text
http://localhost:8080
```

When running with Maven directly, PostgreSQL must also be available at the configured datasource URL. The Docker Compose deployment is recommended when you want to avoid manually configuring the environment.

Open that address in a browser.

To stop the application:

```text
Ctrl+C
```

## How to use the tool

### Step 1 — Open the application

Open:

```text
http://localhost:8080
```

The application starts in the **WAITING FOR REPORTS** state.

### Step 2 — Select automation ZIP files

Click **Drop report ZIPs here** or choose the ZIP files from your computer.

You can select:

- A single automation report ZIP
- Multiple automation report ZIPs
- Multiple ZIPs containing split results
- ZIPs containing nested Tradefed result archives

The ZIP must contain a Tradefed:

```text
test_result.xml
```

The parser recursively searches nested ZIP files as well.

### Step 3 — Analyze reports

Click:

```text
Analyze Reports →
```

The server:

1. Reads the uploaded ZIPs.
2. Searches recursively for `test_result.xml`.
3. Reads available report metadata.
4. Detects build information.
5. Parses modules and individual test results.
6. Merges duplicate/split/rerun results.
7. Builds the dashboard.

For large automation reports, parsing may take some time.

### Step 4 — Review build information

The dashboard displays:

- Build fingerprint
- Android version
- Security patch

If multiple different build fingerprints are detected, the application reports:

```text
BUILD MISMATCH
```

Test/module aggregation is intentionally stopped for mixed builds so results from different builds are not incorrectly combined.

### Step 5 — Review results

The dashboard provides consolidated report information including:

- Total modules
- Completed modules
- Incomplete modules
- Passed tests
- Failed tests
- Assumption failures
- Ignored tests
- Total tests
- Completion status

### Step 6 — Review incomplete modules

The **Incomplete Modules** section identifies modules whose Tradefed result indicates:

```text
done=false
```

These should be reviewed separately from ordinary testcase failures.

### Step 7 — Review failed test cases

The **Failures** section lists the final failed testcase results after rerun reconciliation.

For repeated executions of the same testcase:

```text
PASS + FAIL  -> PASS
FAIL + PASS  -> PASS
PASS + PASS  -> PASS
FAIL + FAIL  -> FAIL
```

This is important for automation reruns. A testcase that passes in at least one execution is considered passed by the parser.

### Step 8 — Publish the dashboard

After analysis:

- **View Dashboard** opens the generated stakeholder-facing dashboard.
- **Download Dashboard HTML** downloads the dashboard as a standalone HTML file.

The downloaded report can be shared independently of the running application.

### Step 9 — Clear the current analysis

Click **Clear** to remove the currently selected reports and reset the dashboard.

The application returns to:

```text
WAITING FOR REPORTS
```

## Large report handling

Automation reports can contain very large `test_result.xml` files.

The parser uses a **StAX streaming XML parser** rather than DOM. This means the complete XML document is not loaded into a large in-memory DOM tree.

The application is also configured with:

- Maximum individual upload: **500 MB**
- Maximum total multipart request: **1 GB**
- Tomcat connection timeout: **120 seconds**
- Spring Boot parser JVM heap: **512 MB initial / 4 GB maximum**

For very large automation runs, use a machine with adequate RAM.

## Build grouping and merge rules

Reports are grouped using:

```text
Build Fingerprint
```

Reports with different fingerprints are never merged.

When multiple automation reports use the same build fingerprint, their results can be consolidated into the same analysis.

## Report structure expected

A normal Tradefed archive may look like:

```text
AutomationReport.zip
└── .../
    └── test_result.xml
```

Nested result archives are also supported:

```text
AutomationReport.zip
├── .../test_result.xml
├── .../result.zip
│   └── .../test_result.xml
└── .../another-result.zip
    └── .../test_result.xml
```

The parser recursively searches ZIP files up to the configured nesting depth.

## Troubleshooting

### `mvn: command not found`

Install Maven and verify:

```bash
mvn -version
```

### `java: command not found`

Install Java 17+ and verify:

```bash
java -version
```

### Browser cannot open `localhost:8080`

First verify that the application is running:

```text
mvn spring-boot:run
```

On Linux/WSL, also check:

```bash
ss -lntp | grep 8080
```

If the application is listening inside WSL but Windows cannot access `localhost:8080`, try the WSL IP address or restart WSL networking.

### Upload is rejected

Check the ZIP size against the configured limits:

```text
Individual file: 500 MB
Total request:    1 GB
```

### Report metadata is not detected

Open the Tradefed `test_result.xml` and verify that the result metadata is present in the root `Result` element.

The parser uses the available report metadata and does not depend on the uploaded ZIP filename.

### Build mismatch is displayed

Check the build fingerprints of the uploaded reports. Reports from different builds are intentionally not combined.

### Parser runs out of memory

The application already uses streaming StAX parsing and a 4 GB maximum heap for the Spring Boot Maven run. If very large reports still exceed available memory, make sure the host has sufficient RAM and avoid uploading unrelated large ZIPs in the same request.

## Development

Run the automated tests:

```bash
mvn test
```

Build the application:

```bash
mvn clean package
```

Run locally:

```bash
mvn spring-boot:run
```

Main project structure:

```text
src/
├── main/
│   ├── java/com/gct/parser/
│   │   └── DashboardController.java
│   └── resources/
│       ├── static/
│       │   ├── index.html
│       │   ├── app.js
│       │   └── style.css
│       └── application.properties
└── test/
    └── java/com/gct/parser/
        └── DashboardControllerTest.java
```

## Repository

GitHub:

https://github.com/bhanuroyal002/GCT_Report_Parser

## License

No license has been specified for this repository yet.
