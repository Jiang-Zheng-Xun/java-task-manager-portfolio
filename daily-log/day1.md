# Day 1: Repository initialization and development baseline

Date: 2026-09-18

## Goal

Establish a private, reproducible, secure, and verifiable development baseline for the Java Task Manager portfolio.

## Delivered

- Verified the WSL2 Ubuntu development environment and Java 21 toolchain.
- Installed and verified GitHub CLI, Docker Engine, Docker Compose, and the PostgreSQL 17 client.
- Enabled systemd in WSL2.
- Created the private GitHub repository and established `main`, `develop`, and `feature/day-1-repository-baseline`.
- Created a Spring Boot 3.5.16 and Java 21 Maven skeleton.
- Established modular package boundaries for API, application, domain, persistence, and configuration concerns.
- Added PostgreSQL local-development configuration with ignored local credentials.
- Added the first Flyway migration for the `tasks` table.
- Added a minimal Spring application-context test.
- Added a GitHub Actions CI workflow using Java 21 and PostgreSQL 17.11.
- Added reproducible local setup and verification instructions.
- Recorded the initial technology decision in ADR 0001.

## Verification evidence

- `mvn clean compile` completed successfully.
- `mvn clean test` completed with one passing test and no failures or errors.
- `mvn clean verify` completed successfully.
- Flyway applied migration V1 successfully.
- The `tasks` table and its baseline constraints were verified.
- PostgreSQL reported that it was accepting connections.
- PostgreSQL listened only on `127.0.0.1:5432`.
- The Spring Boot application started successfully on `127.0.0.1:8080`.
- The root path returned the expected `404` because no Task API endpoint was implemented on Day 1.
- `git diff --check` passed.

## Security evidence

- The repository remained private.
- Local `.env` and `.env.local` files were ignored by Git.
- Candidate repository files contained no local `.env` file.
- No high-risk secret pattern was detected.
- Configuration files contained only environment-variable references, placeholders, and an explicit CI-only test value.
- GitHub Actions uses read-only repository content permission.
- Level 1 and Level 2 security reviews passed.

## Decisions

- Retained the approved Spring Boot 3.5.16 baseline after validating compatibility and Maven artifact availability.
- Created the Maven skeleton manually because Spring Initializr did not expose the approved version.
- Used Docker Compose for the local PostgreSQL service and installed only the PostgreSQL client on WSL.
- Kept local services bound to localhost.
- Deferred Task API and complete domain-rule implementation beyond Day 1.

## Reflection

### Outcomes

Day 1 established the complete repository and development baseline needed for subsequent feature work, with executable local verification and explicit security boundaries.

### Technical learning

When Spring Initializr does not expose an approved Spring Boot version, the version should not be changed automatically. Compatibility, artifact availability, and project requirements should be verified before choosing a manual skeleton or proposing an upgrade.

Environment variables, ignored local files, localhost binding, migrations, application-context tests, and CI each provide different evidence and should be validated independently.

### Problems and resolutions

Spring Initializr rejected Spring Boot 3.5.16 with HTTP 400. Metadata, compatibility, and Maven artifact checks supported retaining the approved version and creating the Maven skeleton manually.

WSL initially did not run systemd. Adding the approved `/etc/wsl.conf` setting and restarting WSL enabled systemd and allowed Docker service management.

### Process improvements

The daily workflow now includes a reviewed Reflection before freezing the Daily Log and opening the primary PR. Final effective time and dynamic delivery results are recorded after Issue closure through `/write-notion/dayN`.

Lunch and dinner reminders are issued near 11:30 and 18:00, and excluded breaks do not reset the daily effective-time limit.

## Evidence boundary

This log records stable implementation, verification, security, decision, and Reflection evidence. Pull request, remote CI, merge, branch cleanup, Issue closure, and final effective-time facts are verified through their dynamic records outside this file.
