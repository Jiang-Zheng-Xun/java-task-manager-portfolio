# Day 6 — PATCH Task Status Vertical Slice

**Date:** 2026-09-25
**Issue:** #12
**Base branch:** `develop`
**Feature branch:** `feature/day-6-patch-status`
**Planned mode:** Light; scope extended by user after the Light target was reached

## Goal and approved contract

Compare PUT and PATCH, then implement one bounded status-update slice:

- `PATCH /api/tasks/{id}/status`
- Request: `{"status":"IN_PROGRESS"}`
- Accepted statuses: `TODO`, `IN_PROGRESS`, `COMPLETED`
- Success: `200 OK` with the complete Task representation
- Invalid ID, missing or invalid status, malformed JSON, or unknown field: safe `400`
- Missing Task: safe `404`
- Preserve `createdAt`; update `updatedAt` on an actual status change
- Repeating the same status leaves the stored state and `updatedAt` unchanged
- Title and description updates remain outside this slice

PATCH fits the single-field change. A future PUT contract must separately define the replacement representation and omitted-field semantics.

## Delivered

- Added `Task.withStatus(status, now)` with unchanged identity and creation time; an unchanged status returns the existing Task.
- Added application use case and transactional Service using the existing `Clock` and repository port. The Service skips `save` when status is unchanged.
- Reused the repository adapter's save path to update an existing Task; PostgreSQL integration evidence reloads after `flush()` and `clear()`.
- Added request DTO and Controller for the PATCH route, using the existing safe error handler and strict unknown-field rejection.
- Updated README with the PATCH contract and corrected its introductory statement about Day 5 bounded pagination.

## Verification and evidence boundaries

- Domain tests verify changed and unchanged status behavior.
- Service unit tests with a mocked repository verify lookup, changed-task save, missing-task exception, and no save on same-status replay. They do not prove database behavior.
- WebMvc tests with a mocked use case verify routing, response mapping, validation, safe `400`/`404`/`500`, and rejection before the use case where applicable. They do not prove the real Service or PostgreSQL path.
- Repository integration test verifies an existing row's status and timestamps after JPA `flush()`, persistence-context `clear()`, and reload.
- Full API integration test verifies POST → PATCH, PostgreSQL status, and same-status replay. The test uses a transaction that rolls back; a `flush()` allows JDBC observation within that transaction. This is not evidence of a committed result observed by a separate transaction.
- The Java `Instant` response may carry nanoseconds while PostgreSQL stores microseconds. The test compares HTTP and stored time within 1 microsecond; it compares the two database timestamps exactly for replay.
- Final pre-delivery `mvn clean verify`: **72 tests**, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.
- `git diff --check`: passed.

## Security and limitations

Level 1 and Level 2 checks covered ignored `.env`, localhost PostgreSQL binding, strict request fields, safe external errors, and absence of new external dependencies, permission expansion, or public network exposure. No secret, personal data, or sensitive log was identified in the changes.

The endpoint updates status only. This delivery does not claim cross-transaction commit observation, authentication, authorization, title/description updates, or production deployment.

## Reflection

The initial baseline failed with 18 test errors because PostgreSQL was stopped after the previous day's `docker compose down`. After starting the project PostgreSQL service, the unchanged baseline passed 57 tests.

The first full API test read `TODO` through JDBC immediately after a successful PATCH response. In the test transaction, JPA had not flushed the update. Adding a test-side `flush()` made the changed status visible to the JDBC query. A later timestamp assertion exposed the precision difference between Java `Instant` and PostgreSQL; the HTTP-to-database comparison now uses a 1-microsecond tolerance, while database-to-database replay comparison remains exact.

The PUT/PATCH explanation and testing-boundary teach-back required corrections: PUT replaces the resource representation under an explicitly defined contract, and the WebMvc test returns a Task JSON object, not an array. These concepts remain Review.

Process follow-ups proposed for separate approval:

1. Preserve a dated schedule baseline, consult it before each Day plan, and propose any material change for user approval before updating that baseline.
2. Add a daily environment-start check after repository baseline: safely confirm `.env` is loaded and whether the PostgreSQL Compose service needs to be started before database-backed Maven tests. Docker operations remain subject to their operation-specific approval rule.

Question Bank candidates require deduplication and review: PUT/PATCH contract semantics, JPA flush and JDBC visibility, test evidence boundaries, and timestamp precision. No automatic promotion to Stable is claimed.
