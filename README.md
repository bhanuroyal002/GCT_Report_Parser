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
- Uses a streaming StAX XML parser to handle large Tradefed result files without loading the entire XML document into memory.

## Supported reports

The parser is designed for **automation and certification result archives** containing Tradefed `test_result.xml` files.

It supports processing certification automation reports, including **CTS Verifier** results.

The tool automatically analyzes the available report data and generates a consolidated dashboard without relying on hardcoded test-suite names.

## Requirements

### Required runtime

| Requirement | Version / Details |
|---|---|
| Java | **17 or later** |
| PostgreSQL | **16+** |
| Git | Required to clone/update the repository |
| Browser | Modern Chrome, Edge or Firefox |
| RAM | **8 GB minimum recommended**; 16 GB+ preferred for large reports |
| Disk space | Sufficient space for uploaded ZIPs and processed reports |

### Build/development only

| Requirement | Version / Details |
|---|---|
| Maven | **3.9+** |

Maven is required only when building or testing the source code. **End users do not need Maven installed** when they receive a built application artifact.

Node.js, Python and Android SDK are not required.

PostgreSQL is used for shared analysis history.

## Clean server installation

This application can run directly on Linux, Windows or macOS with Java and PostgreSQL installed. Docker is **not required**.

### 1. Install Java

Verify:

```bash
java -version
```

Java 17 or later is required.

### 2. Install PostgreSQL

Install PostgreSQL 16 or later and create a database, user and password for the application.

Example:

```sql
CREATE USER gct_parser WITH PASSWORD 'replace-with-a-strong-password';
CREATE DATABASE gct_parser OWNER gct_parser;
```

Do not use these example credentials in a real environment.

### 3. Clone the repository

```bash
git clone https://github.com/bhanuroyal002/GCT_Report_Parser.git
cd GCT_Report_Parser
```

### 4. Build the application

On a development/build machine with Maven:

```bash
mvn clean package
```

The generated application artifact is:

```text
target/gct-report-parser.jar
```

### 5. Configure database access

Set the PostgreSQL connection through environment variables:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/gct_parser
export SPRING_DATASOURCE_USERNAME=gct_parser
export SPRING_DATASOURCE_PASSWORD='your-strong-password'
```

You can also set:

```bash
export MAX_FILE_SIZE=500MB
export MAX_REQUEST_SIZE=1GB
export SERVER_CONNECTION_TIMEOUT=120000
```

### 6. Start the application

End users can start the already-built artifact directly with Java:

```bash
java -Xms512m -Xmx4g -jar target/gct-report-parser.jar
```

The application starts on:

```text
http://localhost:8080
```

**Maven is not required to run the built JAR.**

For a server deployment, copy `gct-report-parser.jar` to the server and run:

```bash
java -Xms512m -Xmx4g -jar gct-report-parser.jar
```

To run it as a managed Linux service, use a systemd unit or the organization's standard service manager.

## Jenkins / Tomcat deployment

The application can also be deployed as a traditional WAR to an external Tomcat server.

For that model:

1. Change the Maven packaging to `war`.
2. Extend `GctReportParserApplication` from `SpringBootServletInitializer`.
3. Mark the embedded Tomcat dependency as `provided`.
4. Build the WAR with Maven.
5. Deploy the WAR from Jenkins to the Tomcat server.

Spring Boot documents this traditional WAR deployment model and the required `SpringBootServletInitializer`, `<packaging>war</packaging>`, and provided Tomcat dependency. citeturn882665search0

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

The application is configured with:

- Maximum individual upload: **500 MB**
- Maximum total multipart request: **1 GB**
- Tomcat connection timeout: **120 seconds**
- JVM heap recommendation: **512 MB initial / 4 GB maximum**

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

## Troubleshooting

### `java: command not found`

Install Java 17+ and verify:

```bash
java -version
```

### `mvn: command not found`

Maven is needed only to build/test from source. If you already have `gct-report-parser.jar`, Maven is not needed to run the application:

```bash
java -jar gct-report-parser.jar
```

### Browser cannot open `localhost:8080`

Verify that the application is running:

```bash
ss -lntp | grep 8080
```

### PostgreSQL connection failure

Verify that PostgreSQL is running and that these environment variables are correct:

```bash
echo "$SPRING_DATASOURCE_URL"
echo "$SPRING_DATASOURCE_USERNAME"
```

Do not print the database password in shared logs.

### Upload is rejected

Check the ZIP size against the configured limits:

```text
Individual file: 500 MB
Total request:    1 GB
```

### Build mismatch is displayed

Check the build fingerprints of the uploaded reports. Reports from different builds are intentionally not combined.

### Parser runs out of memory

The application already uses streaming StAX parsing. If very large reports still exceed available memory, make sure the host has sufficient RAM and increase the JVM heap where appropriate.

## Development

Run the automated tests:

```bash
mvn test
```

Build the application:

```bash
mvn clean package
```

Run locally from source:

```bash
mvn spring-boot:run
```

Run the built artifact without Maven:

```bash
java -Xms512m -Xmx4g -jar target/gct-report-parser.jar
```

Main project structure:

```text
src/
├── main/
│   ├── java/com/gct/parser/
│   │   ├── AnalysisHistoryEntity.java
│   │   ├── AnalysisHistoryRepository.java
│   │   ├── AnalysisHistoryService.java
│   │   ├── DashboardController.java
│   │   ├── GctReportParserApplication.java
│   │   └── HtmlReportBuilder.java
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
