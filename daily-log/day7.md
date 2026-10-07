# Day 7 — Replace Task with PUT

**Date:** 2026-09-28
**Issue:** #14
**Base branch:** `develop` at `33051bccb17c5ba2f79195eaff110f77c06a08d4`
**Feature branch:** `feature/day-7-put-task`
**Planned mode:** Normal, maximum 7 effective hours

## Goal and approved contract

Deliver `PUT /api/tasks/{id}` as a full replacement of the editable
`title`, `description`, and `status` fields.

- All three JSON fields are required.
- Explicit `description: null` clears the description; an omitted `description` is invalid.
- Client-supplied `id`, `createdAt`, `updatedAt`, and unknown fields are rejected.
- Success returns `200 OK` with the complete Task representation.
- Invalid requests return a safe `400`; a missing Task returns a safe `404`; unexpected failures return a safe `500`.
- A changed Task preserves its ID and `createdAt` and updates `updatedAt`. Repeating equivalent normalized content preserves `updatedAt` and skips an unnecessary repository save.
- Existing status transitions remain available, including direct completion and reopening. No `completedAt` field was added.

## Delivered implementation

- Added `Task.replaceEditableFields` to normalize replacement input, preserve identity and creation time, and return the original Task for an equivalent replacement. Existing status-only PATCH behavior was retained.
- Added `ReplaceTaskUseCase` and transactional `ReplaceTaskService`; the service handles invalid IDs, missing Tasks, changed-state save, and no-op save avoidance through the existing repository port.
- Reused the persistence adapter's save path without changing production persistence code or database schema.
- Added `ReplaceTaskRequest` and `ReplaceTaskController`. The request distinguishes an omitted `description` from explicit JSON null at the HTTP boundary; strict unknown-field handling rejects server-managed fields.
- Updated README endpoint lists, PUT contract, PUT/PATCH distinction, and an outdated statement that described PUT as future work.

## Verification and evidence boundaries

- Pre-change local baseline: 72 tests, 0 failures, 0 errors, 0 skipped.
- Domain targeted `TaskTest`: 13 tests passed, including three new replacement cases. The Red step was not performed because the initial instruction omitted executable test code; this is recorded as such.
- Service targeted `ReplaceTaskServiceTest`: 4 tests passed using a mocked repository. These verify service coordination but do not prove database behavior.
- PostgreSQL repository integration: 8 tests passed. The new case verifies replaced fields and preserved identity/creation time after flush, clear, and reload.
- WebMvc targeted `ReplaceTaskControllerTest`: 10 tests passed using a mocked use case. These verify HTTP binding, required description presence, explicit null, unknown fields, and safe error responses; they do not prove the real service or database path.
- Full PostgreSQL API integration: 14 tests passed. The new cases verify POST → PUT → GET, stored replacement fields, unchanged `createdAt`, equivalent replay with exact database `updated_at` equality, and an invalid request leaving the stored row unchanged.
- Final local `mvn clean verify`: 92 tests, 0 failures, 0 errors,  0 skipped; BUILD SUCCESS.
- Full API tests run in a test transaction and flush before JDBC reads. They establish transaction-local database evidence, not observation by an independent transaction after commit.
- Java `Instant` may retain nanoseconds while PostgreSQL timestamps use microsecond precision. The test compares database `created_at` before and after PUT exactly, allows at most 1 microsecond when comparing HTTP and database representations, and compares database timestamps before and after equivalent replay exactly.
- `git diff --check` passed. All 11 changed files, including six new files shown with `git add -N`, were included in the local scope and security review.

## Security and scope

Level 1 and Level 2 review covered strict input handling, safe public errors, ignored `.env`, localhost PostgreSQL binding, changed files, and absence of new dependencies, network exposure, or permission changes. The local diff review found no secret, personal data, or sensitive log in the change.

DELETE, `completedAt`, new transition restrictions, authentication,
deployment, schema changes, and PATCH coverage-gap remediation were outside Day 7 scope.

## Reflection

The main design lesson is that a PUT replacement needs an explicit complete editable representation. Omitted `description` and explicit JSON null have different meanings under this contract, so the HTTP boundary must preserve that distinction.

The full API test initially compared an HTTP nanosecond `createdAt` to a PostgreSQL microsecond timestamp for exact equality and failed. The revised test checks the database value before and after PUT exactly and compares cross-representation values with a 1-microsecond tolerance.

Instructions for code changes should provide executable test code, a file path, and the insertion location. README review should search for stale statements throughout the document. The missing domain Red step was reported rather than reconstructed after implementation.

For Day 11 evidence review, consider whether to add a PATCH full API completion/reopen scenario and an independent post-commit transaction observer. These are recorded as coverage questions, not verified defects; neither is automatically approved for Day 7.
