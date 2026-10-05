# Day 12: Documentation integration and complete local API demo

Date: 2026-10-05
Mode: Normal
Local single writer: user

## Outcomes

- Corrected the README scope to include Task deletion.
- Added an API overview and links to architecture, ADR and demo documentation.
- Documented implemented responsibilities, dependency direction,  representations, transaction boundaries and verification limits.
- Reviewed ADR 0001 and retained its historical technology decision. No new technology decision required another ADR.
- Documented and executed a complete localhost curl demo using a generated ID.
- Production code, dependencies, API contracts, schema, CI configuration and permissions were unchanged.

## Verified evidence

### Baseline

The user reported local develop and origin/develop at
44baf57100f1758d1bc2e24cc922fda4a200870b, clean working tree,
zero divergence and removal of the previous local feature branch.
Remote develop was independently checked against that commit.

Before documentation work, the user reported complete Maven verification:
123 tests, zero failures, errors and skipped tests, BUILD SUCCESS.
This is local user-reported evidence, not a new CI result.

### Live local demo

The supplied startup log confirmed the application started.
POST returned 201 with generated ID 1540, TODO and Location /api/tasks/1540. The user confirmed the Location assertion passed.

GET returned the original Task. Listing page 0 with size 2 returned IDs 32 and 1540 in ascending order, with X-Has-Next-Page false. This single-page observation does not prove all pagination scenarios.

PATCH completed the Task directly, then reopened it to IN_PROGRESS. Each subsequent GET returned the changed status while identity, title, description and creation time were preserved.

PUT returned the replacement title, null description and COMPLETED. The subsequent GET confirmed those values and retained identity and creation time.

The user reported the initial DELETE returned 204; its response headers were not attached. The supplied output confirmed an empty body check, GET 1540 returning safe JSON 404 and repeated DELETE returning safe JSON 404.

GET 32 confirmed the pre-existing Task retained its original fields and timestamps. Only the demo-owned Task was modified and deleted.

The user reported temporary response files and shell state cleaned up, the application stopped with Ctrl+C and PostgreSQL retained for verification.

### Documentation and security review

Complete README diff, architecture text and demo text were reviewed. The user reported diff checks passed and Level 1/2 review passed. The ignored .env remained outside the changes; documentation contained no secrets or personal data. No bulk deletion or volume removal was performed.

At the review, remote main and develop were reported as unprotected. The architecture document distinguishes CI execution from branch protection; ADR 0001's reference is historical rationale, not proof of enabled protection.

## Reflection

### Technical understanding

Runtime calls and source-code dependency direction are different:
services depend on the repository port defined in the application layer, while the persistence adapter implements it.

The application layer uses Spring service and transaction annotations; separation from persistence does not make it framework-free.

MockMvc/PostgreSQL tests and live curl observations provide different evidence. Flush and clear do not prove commit. JDBC observation after the write transaction completes does not require another physical pooled connection.

New HTTP timestamps can retain nanoseconds while persisted values use microseconds. Observed PATCH/GET differences of 400ns and 335ns and a PUT/GET difference of 309ns were within the existing 1µs precision boundary. Unchanged persisted values still require exact comparisons where applicable.

### Problems and corrections

A missing space before the Hibernate inline-code setting was corrected.

Assistant guidance initially mixed document creation with execution and supplied a result form covering steps not yet explicitly performed. ID extraction then failed with FileNotFoundError for /body because demo_dir had not been initialized. This was a guidance/precondition error, not an application defect or Maven test failure.

The workflow was corrected to provide explicit terminal, shell, prerequisites, commands and stop points for each segment. Response forms cover only that segment; expected defaults are not treated as verified results.

### Reusable candidates

Prefer deduplication against existing questions about test evidence boundaries and architecture dependency direction. No new oral assessment was performed; this record does not change Mastery or Last Reviewed.

## Evidence limits

The demo verifies selected behavior of a running localhost application. It does not establish deployment readiness, all validation/security cases, concurrency guarantees or production-scale pagination.

Local environment and cleanup results remain distinct from remote GitHub evidence. Shared Question Bank Stage 2 aggregate validation remains incomplete.
