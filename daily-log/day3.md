# Day 3 — GET Task by ID Vertical Slice

**Date:** 2026-09-22
**Issue:** #5
**Base branch:** `develop`
**Feature branch:** `feature/day-3-get-task-by-id`

## Goal

Deliver a verified `GET /api/tasks/{id}` vertical slice across the API, application, persistence, and PostgreSQL layers, while maintaining safe error responses and REST consistency with `POST /api/tasks`.

## Delivered

- Added `Optional<Task> findById(long id)` to the repository output port.
- Implemented the JPA repository adapter read path and entity-to-domain mapping.
- Added `GetTaskByIdUseCase` and `GetTaskByIdService`.
- Added framework-independent `TaskNotFoundException`.
- Added `GET /api/tasks/{id}`.
- Returned the complete Task representation for an existing Task.
- Returned safe `404 Not Found` for a missing positive ID.
- Returned safe `400 Bad Request` for:
  - zero
  - negative IDs
  - non-numeric IDs
  - values outside the Java `Long` range
- Preserved the safe generic `500 Internal Server Error` response.
- Replaced the create-only response DTO with shared API DTO `TaskResponse`.
- Added `Location: /api/tasks/{createdId}` to successful POST responses.
- Renamed the full API integration test class to `TaskApiIntegrationTest`.
- Updated README scope, API contracts, curl examples, and read-path architecture.

## API Contracts

### Successful lookup

`GET /api/tasks/{id}` returns `200 OK` with:

- `id`
- `title`
- `description`
- `status`
- `createdAt`
- `updatedAt`

### Missing resource

A valid positive ID that does not exist returns `404 Not Found` using the existing safe `ApiErrorResponse` structure.

### Invalid path ID

- `0` and negative IDs are rejected by the application service before repository access.
- Non-numeric and overflowing values fail during Spring path-variable binding.
- Both paths return safe `400 Bad Request` responses without exposing framework details.

### POST consistency

A successful `POST /api/tasks` continues to return `201 Created` with the complete Task representation and now includes:

`Location: /api/tasks/{createdId}`

## Read Path

```text
GET /api/tasks/{id}
→ GetTaskByIdController
→ GetTaskByIdUseCase
→ GetTaskByIdService
→ TaskRepository
→ TaskRepositoryAdapter
→ TaskJpaRepository
→ PostgreSQL
→ TaskMapper
→ domain Task
→ TaskResponse
→ 200 OK
```

## Verification Evidence

- Repository integration tests verified:
  - saving a Task
  - reading an existing Task by ID
  - returning empty for a missing valid ID
- Application service tests verified:
  - found behavior
  - missing behavior
  - rejection of zero and negative IDs before repository access
- WebMvc tests verified:
  - `200 OK`
  - safe `400 Bad Request`
  - safe `404 Not Found`
  - safe `500 Internal Server Error`
  - POST `Location` behavior
- Full PostgreSQL API integration tests verified:
  - existing POST behavior
  - POST followed by GET using the database-generated ID
  - missing Task `404`
  - POST `Location` matching the generated ID
- Manual curl verification confirmed:
  - POST returned `201 Created`
  - generated Task ID was `32`
  - `Location` was `/api/tasks/32`
  - GET through that Location returned `200 OK`
  - POST and GET representations matched

## Security Review

Level 1 and Level 2 reviews passed.

Verified controls:

- No real password, token, credential, or `.env` value was added.
- PostgreSQL and manual Spring Boot verification remained localhost-only.
- Invalid IDs are rejected safely.
- Error responses do not expose raw exceptions, SQL, JDBC endpoints, credentials, or internal Java class details.
- Repository lookup relies on JPA parameter binding rather than SQL string construction.
- No dependency, authentication claim, destructive operation, or out-of-scope feature was introduced.

## Learning and Reflection

The repository reports whether data exists through `Optional<Task>`. The application service validates input and converts an empty result into an application exception. The global exception handler translates application and binding failures into safe HTTP responses. This keeps persistence, application, and HTTP responsibilities separated.

The main review topic remains the detailed call and conversion flow between the controller, use case, service, repository port, adapter, JPA repository, mapper, domain model, and API DTO.

## Process Improvements

- Keep cross-chat response forms consistent, copyable, traceable, and open to improvement.
- Prefill reasonable defaults for expected low-risk confirmations; change them only when evidence differs.
- Before Maven or Spring Boot commands in a new shell or chat, safely verify that `.env` and required environment variables are loaded without printing secret values.
- Continue using small Red/Green steps to isolate contract gaps.
- Keep teach-back focused on Weak and Review topics so closure work is not compressed.

## Question Bank Candidates

- Responsibilities and data transformations across the GET-by-ID read path.
- How `Optional.empty()` becomes a safe HTTP `404`.
- Spring binding failure versus application validation failure.
- Evidence boundaries of WebMvc, repository integration, and full API integration tests.
