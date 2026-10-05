# Architecture and evidence boundaries

## Scope

This project is a modular monolith with one Task module. It provides a verifiable backend product-core prototype, not a deployed commercial service. The implemented API supports creation, bounded listing, reading, status updates, replacement of editable fields, and physical deletion.

## Responsibilities and dependencies

| Area | Responsibility | Main dependencies |
| --- | --- | --- |
| `task.api` | HTTP routing, request validation, DTO mapping, response status and headers, safe error mapping | Application input ports and domain types |
| `task.application` | Use-case orchestration, transaction boundaries, missing-resource handling | Domain types, repository output port, `Clock` where needed |
| `task.domain` | Task normalization, invariants, status changes and replacement rules | Java standard library |
| `task.infrastructure.persistence` | Repository implementation, entity mapping, ordered bounded queries | Application output port, domain, Spring Data JPA |
| `configuration` | Shared application wiring, including the clock | Spring configuration |

Controllers depend on use-case interfaces. Services implement those interfaces and depend on the `TaskRepository` output port. The persistence adapter implements that port using `TaskJpaRepository` and `TaskMapper`.

Runtime calls proceed from controller to service to persistence adapter. The application defines the repository interface; it does not depend on the concrete JPA adapter. Spring supplies implementations through constructor injection. This separation does not mean the application layer is framework-free: services use Spring service and transaction annotations.

Interfaces represent use-case and persistence boundaries. DTOs and entities are not passed interchangeably across these boundaries.

## Representations and rules

Request DTOs describe accepted client input. `TaskResponse` maps a domain Task into the public response. `TaskEntity` represents persistence state; `TaskMapper` converts between entity and domain representations.

The immutable domain `Task` normalizes title and description, checks required values and length limits, initializes new Tasks as `TODO`, and preserves identity and creation time during updates.

PATCH changes only status. PUT requires title, description, and status; explicit `description: null` clears the description, while omission is invalid. The PUT DTO distinguishes omission from explicit null using `JsonNode`. Unknown request fields are rejected by the configured Jackson deserializer.

An unchanged status or equivalent normalized replacement returns the existing domain Task. The corresponding service skips saving it, preserving the stored update timestamp. Current status rules allow direct completion and reopening.

## Transactions and persistence

Create, PATCH, PUT, and DELETE services define write transactions.
GET-by-ID and list services use read-only transactions. The service boundary covers the complete use-case operation rather than individual controller mapping steps.

The repository adapter maps domain Tasks to JPA entities for saving and maps queried entities back to domain Tasks. Listing uses an ascending-ID JPA `Slice`, bounded page size, and a `hasNext` result rather than total counts.

Flyway owns versioned schema changes. V1 creates the Task table, generated ID, required fields, length limits, and title/status constraints. Hibernate `ddl-auto: validate` checks the mapping against the schema; it does not replace migration management. `open-in-view` is disabled.

Offset pagination does not provide a snapshot across separate requests. Concurrent inserts or deletes can change page contents. The current design does not claim production-scale performance or concurrency guarantees beyond the implemented and tested behavior.

## Validation and errors

HTTP binding and JSON parsing, DTO validation, application/domain rules, and database constraints protect different boundaries. The exact failure location depends on the request; a nonnumeric ID fails binding, while a non-positive bound ID can be rejected by the application before repository lookup.

The HTTP exception handler maps failures to safe public responses. Supported error behavior includes 400, 404, 405, 415, and 500 JSON responses. Representative unsupported response media-type requests return 406 with an empty body. Protocol headers such as `Allow` are preserved.

These responses do not expose stack traces, SQL, credentials, database endpoints, or internal class names. API validation tests alone do not prove database constraint enforcement; direct JDBC tests supply that evidence.

## Verification boundaries

| Evidence | What it demonstrates | Important limit |
| --- | --- | --- |
| Domain/service unit tests | Rules and orchestration under controlled inputs | Mocked repositories do not prove persistence |
| WebMvc tests | MVC binding, routing, JSON and error contracts | Mocked use cases do not prove real service/database behavior |
| Repository integration tests | Adapter, mapping, JPA and PostgreSQL behavior | Flush/clear/reload does not by itself prove commit |
| Full-context MockMvc/PostgreSQL tests | Integrated application request/response path and real database behavior | They do not start a live network server |
| Tests without an outer test transaction | Service transaction completion followed by JDBC observation outside the write transaction | They do not require a different physical pooled connection |
| Local curl demo | External HTTP behavior of a running localhost application | It does not prove deployment readiness or all test scenarios |
| CI | Verification on the configured runner and service environment | Success does not prove local environment state or fully pinned tooling |

Most API integration tests use a rollback-managed test transaction. `flush()` synchronizes pending SQL; `clear()` clears the persistence context. Neither operation is commit evidence.

Separate PATCH and DELETE tests disable the outer test transaction with `Propagation.NOT_SUPPORTED`. They observe state after the service transaction completes and clean up only their own created Task IDs.

HTTP timestamps can retain nanoseconds while PostgreSQL stores microseconds. Cross-source comparisons of newly generated timestamps allow at most 1µs. Unchanged database values and replay timestamps sourced from persisted values are compared exactly. This tolerance is not applied indiscriminately.

## Local operation and delivery

Local application and PostgreSQL bindings default to localhost.
Real local credentials belong in the ignored `.env`; reload it in each shell that needs the environment. The example environment file contains placeholders.

CI runs Maven verification with Java 21 and PostgreSQL 17.11. CI execution and branch protection are separate controls: at the Day 12 review, GitHub reported both `main` and `develop` as unprotected. ADR 0001's reference to protected branches is historical rationale, not evidence that protection is enabled.
No permission change is implied by this documentation.

The canonical Issue holds the dynamic delivery checklist. Daily Logs preserve reviewed stable outcomes and Reflection; later CI, merge, cleanup and closure metadata are recorded separately.

## Decisions and limits

[ADR 0001](adr/0001-technology-baseline.md) records the initial technology decision and its historical context. This architecture document describes the current implementation; it does not introduce a new technology decision.

Authentication, authorization, filtering, search, client-selected sorting, public deployment, and commercial validation remain outside the current scope.
