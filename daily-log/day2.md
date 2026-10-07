# Day 2: Create Task API vertical slice

Date: 2026-09-21

## Goal

Deliver the first executable Task creation vertical slice through
`POST /api/tasks`, with explicit API, domain, persistence, validation, testing, documentation, and security evidence.

## Delivered

- Added the Task domain model with creation invariants, normalization, and initial `TODO` status.
- Added a separate JPA entity, mapper, Spring Data repository, and repository adapter.
- Added the `CreateTaskUseCase`, `CreateTaskCommand`, and transactional `CreateTaskService`.
- Injected `Clock` into the application service and configured a production UTC clock.
- Added `POST /api/tasks` with separate request and response DTOs.
- Added request validation for required titles and approved length limits.
- Enabled strict rejection of unsupported JSON fields.
- Added stable and safe `400 Bad Request` and
  `500 Internal Server Error` responses.
- Added domain, mapper, persistence, application-service, web-layer, and complete API integration tests.
- Added API documentation and reproducible `curl` examples to the README.
- Completed Level 1 and Level 2 security reviews.

## API contract

### Request

```http
POST /api/tasks
Content-Type: application/json
```

```json
{
  "title": "Prepare portfolio README",
  "description": "Add API examples"
}
```

The client does not supply `id` or initial `status`.

### Success

- Returns `201 Created`.
- Returns the complete created Task representation.
- Uses a database-generated `id`.
- Initializes status to `TODO`.
- Uses the injected UTC clock for `createdAt` and `updatedAt`.
- Does not advertise a `Location` URI before a GET-by-ID contract exists.

### Validation and errors

- Rejects blank titles.
- Rejects titles longer than 200 characters.
- Rejects descriptions longer than 2000 characters.
- Rejects unsupported fields such as client-supplied `id` or `status`.
- Returns safe and actionable `400 Bad Request` responses.
- Returns a fixed safe message for unexpected `500 Internal Server Error` responses.
- Does not expose stack traces, SQL, database endpoints, credentials, internal class names, or raw unexpected exception details.

## Architecture

The create path is:

```text
POST /api/tasks
→ CreateTaskController
→ CreateTaskRequest
→ CreateTaskCommand
→ CreateTaskUseCase
→ CreateTaskService
→ Task.create(...)
→ Task compact constructor
→ TaskRepository
→ TaskRepositoryAdapter
→ TaskMapper
→ TaskJpaRepository
→ PostgreSQL
→ CreateTaskResponse
→ 201 Created
```

Responsibilities remain separated:

- API DTOs define the external JSON contract and HTTP validation.
- The domain model owns business invariants and normalization.
- The application service coordinates creation, time, persistence, and the transaction boundary.
- The persistence entity and adapter isolate JPA and database mapping from the domain.

## Verification evidence

- The complete local Maven test suite passed with 24 tests, no failures, no errors, and no skipped tests.
- Domain tests verified creation rules, normalization, initial status, and timestamp invariants.
- Mapper tests verified domain-to-entity and entity-to-domain mapping.
- The repository adapter integration test verified PostgreSQL persistence and a database-generated ID.
- The application-service unit test verified fixed-clock use, repository interaction, and the returned saved Task.
- Web-layer tests verified the `201`, `400`, and safe `500` contracts.
- The complete API integration test exercised the Spring application through MockMvc, the application service, domain model, repository adapter, JPA, and PostgreSQL.
- A valid request returned a server-generated ID and the same Task was read back from PostgreSQL.
- Blank titles, overlong titles, overlong descriptions, and unsupported fields returned `400` without creating database rows.
- The existing application-context test continued to pass.
- `git diff --check` reported no tracked-file whitespace errors before the delivery stage.

## Security evidence

- The repository remained private.
- The local `.env` file was ignored and was not tracked by Git.
- PostgreSQL remained bound to `127.0.0.1`.
- The application default address remained `127.0.0.1`.
- Client input could not supply a database ID or initial Task status.
- Invalid requests were verified not to create database rows.
- Unexpected exceptions returned a fixed public message rather than their raw exception text.
- Source, tests, documentation, configuration, and workflow files contained no real credentials, tokens, API keys, or private keys.
- Existing dependencies were sufficient; no unreviewed dependency was added.
- Production repository visibility was not widened for integration testing.
- Level 1 and Level 2 security reviews passed.

## Decisions

- Kept API DTOs, the domain model, and the JPA entity separate.
- Used `Task.create(...)` for new-Task defaults and the compact constructor for normalization and invariant enforcement.
- Placed `@Transactional` on the complete application create use case.
- Injected `Clock` rather than calling the system clock directly in the application service.
- Did not return a `Location` header before approving a GET-by-ID resource contract.
- Enforced the approved strict-request policy for unsupported JSON fields.
- Retained package-private visibility for `TaskJpaRepository`.
- Used `JdbcTemplate` in the full API integration test to verify database state without widening production visibility.
- Deferred a custom domain-validation exception beyond the current slice.

## Reflection

### Outcomes

Day 2 delivered the first complete Task creation vertical slice from HTTP input through domain rules and PostgreSQL persistence, with safe responses, documentation, and layered test evidence.

### Technical learning

A correct HTTP status alone is insufficient evidence. Domain rules, transaction behavior, and the final database state must also be verified.

Different test levels answer different questions. `@WebMvcTest` verifies JSON binding, validation, controller behavior, and exception mapping while using a mocked use case. The full `@SpringBootTest` API integration test verifies the complete application path and actual PostgreSQL effects.

A rejected request must be checked for both its public `400` response and the absence of a database row. This distinguishes response behavior from final persistence state.

### Problems and resolutions

The initial validation tests did not cover the approved strict-request policy.
Rechecking Issue #3 exposed the gap, after which unknown-field rejection and its test were added.

The API integration test could not access the package-private
`TaskJpaRepository` from the API package. Instead of widening production visibility for a test, the test used `JdbcTemplate` to verify the persisted database row directly.

The initial README insertion was difficult to copy because nested Markdown fences closed the outer block. A four-backtick outer fence preserved the complete documentation block.

### Learning assessment

Most architecture and test code began from AI-provided examples. The work was executed incrementally with Red/Green evidence and follow-up questions, but the complete layered structure is not yet fully independent knowledge.

Future understanding checks should use Question Bank review, short teach-back explanations, and small no-prompt modifications. A complete no-reference rewrite should be used later as a higher-level milestone rather than as a daily requirement.

### Process improvements

- After each Issue update, restate the completed item, next item, and remaining checklist.
- After the normal time limit, reload the canonical Issue before continuing.
- Include a short responsibility and call-path explanation with implementation guidance.
- Add a consolidated architecture or flow visualization after completing a vertical slice.
- Use Reflection for outcomes, difficulties, understanding, and improvements; schedule technical checks separately.
- Prefer targeted tests during implementation and reserve repeated full-suite verification for stage gates.
- Provide complete copyable response forms and use safe outer fences for nested Markdown.

## Deferred work

- GET, update, completion, and deletion APIs remain outside the current slice.
- A dedicated domain-validation exception may later replace broad
  `IllegalArgumentException` handling.
- Structured field-level validation details may be considered later.
- The documented `curl` examples were reviewed but not manually executed on Day 2; automated API integration tests provide the current execution evidence.
- Independent explanation and small no-prompt implementation exercises remain future learning work.

## Evidence boundary

This log records stable implementation, local verification, security, documentation, decisions, and reviewed Reflection evidence. Pull request, remote CI, merge, post-merge validation, branch cleanup, Issue closure, and final effective-time facts are verified through their dynamic records outside this file.
