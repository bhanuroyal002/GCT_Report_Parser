# GCT Report Parser

Demo application for parsing Android certification/test-suite reports and presenting a unified readiness dashboard.

## Initial Demo Scope

V1 focuses on the basic workflow:

1. Open the dashboard.
2. Upload/select test-suite report files.
3. Parse demo report data.
4. Show suite-level and overall test statistics.
5. Highlight incomplete modules and failed cases.

## Planned Test Suites

- CTS
- GTS
- TVTS
- STS
- VTS
- CTS-on-GSI

## Planned Technology

- Java 17
- Spring Boot
- Maven
- PostgreSQL
- HTML/CSS/JavaScript
- Docker

The first version intentionally uses demo data so the UI and workflow can be validated before adding real report parsing.
