# GCT Report Parser

Spring Boot web application for combining Android certification-suite reports into a unified release-readiness dashboard.

## Workflow
1. Welcome page.
2. Upload ZIP reports for CTS, GTS, TVTS, STS, VTS and CTS-on-GSI.
3. Analyze uploaded ZIPs on the server and build suite-level metrics, completion status, failure markers, build fingerprint and security patch.
4. Highlight incomplete modules and failed cases.
5. Download the dashboard as a standalone HTML report.

## Run
Requires Java 17+ and Maven 3.9+.

    mvn clean test
    mvn spring-boot:run

Open `http://localhost:8080`.

## Parser scope
The first real parser version is dependency-light and heuristic. It detects the suite from the ZIP filename and scans XML/JSON/TXT entries for result/failure/module markers and build metadata. Android Tradefed report formats vary by release, so suite-specific XML parsing should be added for production-grade accuracy.
