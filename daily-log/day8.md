# Day 8 — Delete Task and repeated-delete semantics

**Date:** 2026-09-29
**Issue:** #16
**Base branch:** `develop` at `aec0009a987daab9f1d848e5897a173934aa324d`
**Feature branch:** `feature/day-8-delete-task`
**Planned mode:** Normal, maximum 7 effective hours

## Goal and approved contract

Deliver physical deletion through `DELETE /api/tasks/{id}`.

- Deleting an existing Task returns `204 No Content` with an empty body.
- Deleting a valid ID that does not exist, including a repeated deletion, returns a safe `404 Not Found`.
- Zero, negative, nonnumeric, and out-of-range IDs return a safe `400 Bad Request`.
- Unexpected failures return a safe `500 Internal Server Error`.
- A successful deletion removes the Task from GET-by-ID and collection results.
- Soft deletion, batch deletion, authentication, schema changes, deployment, and Day 11 PATCH coverage-gap remediation were outside the approved scope.

## Delivered implementation

- Added `deleteById(long)` to the Task repository output port. The PostgreSQL adapter returns whether a Task was found and deleted.
- Added `DeleteTaskUseCase` and transactional `DeleteTaskService`. The service rejects non-positive IDs before repository access and maps a missing Task to `TaskNotFoundException`.
- Added `DeleteTaskController` for `DELETE /api/tasks/{id}`. It returns an empty `204` after success and uses the existing exception handler for safe errors.
- Updated README endpoint lists, DELETE contract, example, repeated-delete semantics, and an outdated exclusion statement. No database schema or dependency change was needed.

## Verification and evidence boundaries

- Pre-change local baseline: `develop` and `origin/develop` at `aec0009a987daab9f1d848e5897a173934aa324d`; working tree clean; PostgreSQL healthy; 92 tests passed with no failures, errors, or skips.
- Repository integration: 10 tests passed. New cases verify deletion and preservation of another Task, plus a false result for a missing ID. The test flushes and clears before reloading within its test transaction.
- Application service: 3 focused tests passed using a mocked repository. They verify successful coordination, missing Task, and invalid ID without repository access; they do not prove database behavior.
- WebMvc: 7 focused tests passed using a mocked use case. They verify empty `204`, safe `400`/`404`/`500`, and ID conversion; they do not prove the real service or persistence path.
- Full HTTP/PostgreSQL integration: 16 tests passed. New tests verify POST → DELETE → GET 404, collection absence, repeated DELETE 404, invalid ID leaving another Task intact, and a JDBC observation after the delete use case commits.
- The main API test class is transactional, so its first deletion scenario proves behavior within the test transaction. The separate non-transactional test observes the result after the HTTP delete use case commits; it cleans up only its own created ID.
- The targeted test counts above are per test class and must not be added together as a unique full-suite total.

## Security and scope

Level 1 and Level 2 review covered input and ID handling, safe public errors, changed and new files, ignored `.env`, localhost PostgreSQL binding, and the absence of new dependencies, schema, permission, or network exposure changes. The reviewed local changes contained no secret, personal data, or sensitive log. `git diff --check` passed.

## Reflection

A repeated DELETE may return `404` under this API contract while leaving the resource in the same absent state. The HTTP response and the resulting resource state should be described separately.

Repository, mocked service, WebMvc, and full API tests establish different evidence. A flush and JDBC read inside a test transaction do not by themselves prove visibility after commit; the non-transactional test provides the latter observation for this DELETE path.

The initial README instruction had a truncated nested code fence and lacked exact replacement text. Supplying a complete copyable section with four-backtick outer fencing and exact sentence replacements resolved the documentation step. Future code and documentation instructions should include the file path, executable content, and relative insertion location.

At the start of each day, compare local and remote repository state with the carried handoff baseline and check the environment. At closure, compare the final repository state with that starting baseline and verify `docker compose down` removed the project's containers and network while retaining the volume and data.

Potential Question Bank items are repeated DELETE HTTP semantics and the evidence difference between a test transaction and a post-commit observation. Review meaning and existing entries before any upsert.
