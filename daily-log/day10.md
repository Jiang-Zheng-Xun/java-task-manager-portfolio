# Day 10 — HTTP error boundaries and safe responses

Date: 2026-10-01 (Asia/Taipei)
Mode: Normal
Issue: #20

## Goal and scope

Preserve correct HTTP semantics and safe responses for unsupported request methods, unsupported request media types, and unacceptable response media types.

The approved scope covers representative 405, 415, and 406 boundaries, WebMvc and full-context integration evidence, README documentation, and Level 1/2 security review.

## Verified outcomes

- Before the change, three WebMvc boundary tests produced two failures:
  unsupported method and request Content-Type returned 500 instead of 405 and 415. The representative 406 test already passed.
- Added dedicated handlers for HttpRequestMethodNotSupportedException and HttpMediaTypeNotSupportedException.
- The 405 and 415 responses use the existing five-field ApiErrorResponse  and fixed safe messages. Spring-provided protocol headers are preserved, including Allow for 405.
- No dedicated 406 handler was added. GET /api/tasks with
  Accept: application/xml returns 406 with an empty body in both test layers.
- README documents the representative requests and evidence boundaries.

## Verification

User-reported local Maven results:

- Pre-change full regression: 115 tests passed.
- Handler and existing POST WebMvc regression: 9 tests passed.
- API integration and boundary WebMvc tests: 22 tests passed.
- Full clean verify after the change: 121 tests, zero failures, errors, or skipped tests; BUILD SUCCESS at 11:46:55 CST.
- git diff --check passed.

The full suite adds six tests: three WebMvc and three integration tests. Targeted runs overlap with the full suite and are not added to its total.

## Evidence boundaries

WebMvc tests verify that representative 405 and 415 requests do not call the mocked use cases. Full-context MockMvc integration tests use the real Spring configuration and PostgreSQL environment; they verify response semantics and unchanged database row counts for those requests.

These tests do not establish that all response negotiation occurs before business logic executes, nor do they constitute a live network-server test. The representative 406 assertion verifies an empty response body.

## Security review

Level 1/2 review found no new dependencies, migrations, permissions, or network exposure. The ignored .env remains outside version control. The new responses use fixed public messages. Integration assertions check the five-field response shape and absence of selected internal diagnostics.

Authentication, authorization, deployment, schema changes, and the deferred PATCH evidence work remain outside this delivery.

## Reflection

### Technical learning

A broad Exception handler can intercept exceptions carrying HTTP protocol semantics. Safe handling must preserve the correct status and relevant headers rather than converting every failure to 500.

A 406 response must not force an incompatible JSON error representation.

### Problems and resolutions

Tests first reproduced the 405/415 status errors, then verified their fixes. The representative 406 behavior was already correct and was left unchanged.

ChatGPT initially counted 17 existing API integration tests; the correct count is 16. The targeted total is 16 existing integration tests plus three new integration tests and three boundary WebMvc tests: 22.

### Process improvement

Use git --no-pager diff when sharing complete changes for review. Distinguish code snippets, full diffs, user-reported local execution, and independently verified remote evidence.

At 11:37:46 CST the user identified that ChatGPT had not synchronized the canonical Issue checklist after verified steps. ChatGPT corrected the tracking omission and read the updated Issue back.

For subsequent completed gates, synchronize the Issue and verify its current state before reporting checklist completion. Compound items are checked only after all their conditions pass. Dynamic delivery state stays in the Issue; the Daily Log preserves stable outcomes.

### Question Bank candidates

- Differences and failure locations of 405, 415, and 406.
- Why safe error handling must preserve status, protocol headers, and response content negotiation.

These are candidates for later deduplication and review. The implementation evidence is not a new oral assessment and does not justify a Mastery upgrade.
