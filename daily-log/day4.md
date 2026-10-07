# Day 4 — List Tasks Collection Vertical Slice

**Date:** 2026-09-23
**Issue:** #7
**Base branch:** `develop`
**Feature branch:** `feature/day-4-list-tasks`

## Goal

Deliver a verified basic `GET /api/tasks` collection read vertical slice across the API, application, persistence, and PostgreSQL layers, with an empty-collection contract and deterministic ascending Task ID order.

## Delivered

- Added `List<Task> findAllByIdAscending()` to the repository output port.
- Added the JPA repository query `findAllByOrderByIdAsc()`.
- Implemented collection entity-to-domain mapping in `TaskRepositoryAdapter`.
- Added `ListTasksUseCase` and `ListTasksService`.
- Applied `@Transactional(readOnly = true)` to the collection read service.
- Added `GET /api/tasks`.
- Returned complete Task representations using the shared `TaskResponse` DTO.
- Returned `200 OK` with `[]` for an empty collection.
- Returned collection results in deterministic ascending Task ID order.
- Preserved the safe generic `500 Internal Server Error` response.
- Added repository, application service, WebMvc, and full PostgreSQL API integration evidence.
- Manually verified the collection GET endpoint through localhost curl.
- Updated README scope, collection contract, curl example, and read-path architecture.
- Kept pagination, client-controlled sorting, filtering, and search outside the Day 4 scope.

## API Contract

### Collection lookup

`GET /api/tasks` returns `200 OK` with a JSON array of complete Task representations containing:

- `id`
- `title`
- `description`
- `status`
- `createdAt`
- `updatedAt`

An empty collection returns:

```json
[]
```

Collection results use deterministic ascending Task ID order.

The Day 4 endpoint does not support pagination, client-controlled sorting, filtering, or search.

## Collection Read Path

```text
GET /api/tasks
→ ListTasksController
→ ListTasksUseCase
→ ListTasksService
→ TaskRepository
→ TaskRepositoryAdapter
→ TaskJpaRepository
→ PostgreSQL
→ TaskMapper
→ List<Task>
→ List<TaskResponse>
→ 200 OK
```

`TaskRepository` defines the domain-oriented application output port. `TaskJpaRepository` performs the ordered persistence query. `TaskRepositoryAdapter` maps each `TaskEntity` into a domain `Task` before returning the collection to the application layer.

## Verification Evidence

- The pre-change baseline passed 39 tests with no failures, errors, or skipped tests.
- Repository integration tests verified:
  - an empty PostgreSQL result
  - multiple Tasks returned in ascending ID order
  - entity-to-domain collection mapping
- Repository targeted verification passed 5 tests.
- Application service tests verified:
  - ordered repository results are returned
  - an empty collection is returned without an exception
- Application targeted verification passed 2 tests.
- WebMvc tests verified:
  - `200 OK`
  - complete ordered JSON representations
  - an empty JSON array
  - a safe generic `500 Internal Server Error`
- WebMvc targeted verification passed 3 tests.
- Full PostgreSQL API integration tests verified:
  - an empty collection
  - POST of multiple Tasks followed by collection GET
  - array size
  - ascending generated IDs
  - complete Task representations
- Full API targeted verification passed 9 tests.
- Manual localhost curl verification confirmed:
  - `GET /api/tasks` returned `200 OK`
  - the response was a JSON array
  - the existing Task contained all required representation fields
  - the operation did not add, modify, delete, or clear database data

The manual result contained one Task, so it did not independently prove ordering. Multi-row repository and full PostgreSQL API integration tests provide the ordering evidence.

## Security Review

Level 1 and Level 2 reviews passed without an immediate security finding.

Verified controls:

- No real password, token, credential, connection string, or `.env` value was added.
- `.env` remained ignored by Git.
- PostgreSQL and manual Spring Boot verification remained localhost-only.
- The controller depends on `ListTasksUseCase`, not the repository or `TaskEntity`.
- JPA entities remain isolated within the persistence layer.
- The ordered query uses a controlled Spring Data JPA method rather than dynamic SQL or client-controlled sorting.
- Error responses do not expose stack traces, SQL, database endpoints, credentials, or internal exception details.
- No authentication, authorization, dependency, destructive operation, or unrelated feature claim was introduced.

The basic collection endpoint is currently unbounded. A large dataset could cause a large response, increased application memory use, and increased database load. Pagination or a result limit requires separate future approval before the endpoint can be considered production-ready.

## Learning and Reflection

The collection read path keeps HTTP, application, domain, and persistence responsibilities separate. The service coordinates the read use case, while deterministic ordering is guaranteed by the persistence query. The adapter prevents JPA `TaskEntity` objects from leaking into the application or API layers.

An empty collection is a successful query result and becomes `200 OK` with `[]`. This differs from a valid positive individual Task ID that does not exist, which becomes `404 Not Found`.

The evidence boundary remains important:

- Repository integration tests prove persistence behavior, mapping, empty results, and ordering.
- WebMvc tests prove routing, status, JSON mapping, and safe HTTP error handling with mocked dependencies.
- Full PostgreSQL API integration tests prove the in-process Spring application path against PostgreSQL.
- Manual curl proves externally observable behavior from the locally running application, subject to the data exercised by that request.

These topics remain at Review and are not automatically promoted to Stable.

## Process Improvements

- Provide a complete, consistent, copyable, and reasonably prefilled response form for every stage.
- Before creating a GitHub Issue, perform an exact-title check at the write boundary and confirm whether the user or ChatGPT is the single writer.
- After a timeout or unknown create result, search for the resource before retrying.
- Prefer targeted evidence, `git status`, and small focused diffs when a long diff may be truncated in chat.
- Check the active shell before providing shell-specific syntax and prefer portable commands where practical.
- Use `git status --short` when untracked files must be included; `git diff --stat` alone does not show them.
- Validate stage timestamps against the actual workflow order to avoid overlapping effective-time calculations.
- Keep manual and automated test evidence boundaries explicit and avoid overstating what a single request proves.
- At the end of each work block, check both effective time and the actual meal period before proposing the next step.
- When the ChatGPT execution environment cannot access the local repository, state the limitation and switch to explicit user-run commands and evidence forms.
- Keep the Daily Log delivery-stable. Record pending CI, merge, cleanup, Issue closure, and final effective time only after they are verified separately.

## Question Bank Candidates

- Responsibilities and data transformations across the collection GET read path.
- Why an empty collection returns `200 OK` with `[]`, while a missing individual resource returns `404 Not Found`.
- Which layer guarantees deterministic collection ordering and why.
- Evidence boundaries of WebMvc, repository integration, full PostgreSQL API integration, and manual curl.
