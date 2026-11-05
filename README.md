# Java Task Manager Portfolio

A verifiable backend portfolio project built with Java 21, Spring Boot 3.5.16, Maven, PostgreSQL 17, Flyway, and GitHub Actions.

## Portfolio status and security limits

This is a local-development backend portfolio prototype. It provides Task and Task Evidence APIs, PostgreSQL persistence, a guarded test database path, and regression and CI evidence. It does not provide a publicly deployed service.

Security review and targeted dependency remediation are documented in [Day 14 security assessment](docs/security-assessment-day14.md). That assessment is dated 2026-10-07 and does not cover the later test database guard, Task Evidence API, or Evidence demo. Known findings, applicability uncertainties, and tool/environment coverage gaps remain. Passing tests and successful scans do not establish complete security validation or production readiness.

Authentication and authorization are outside the current scope.
Use only isolated, replaceable development/test data and retain the documented localhost bindings.

## Current scope

The current prototype provides executable Task creation, read, update, and deletion vertical slices, plus append-only Task Evidence records:

- `POST /api/tasks`
- `GET /api/tasks`
- `GET /api/tasks/{id}`
- `PATCH /api/tasks/{id}/status`
- `PUT /api/tasks/{id}`
- `DELETE /api/tasks/{id}`
- `POST /api/tasks/{id}/evidence`
- `GET /api/tasks/{id}/evidence`
- Request validation and strict rejection of unsupported fields
- Domain-level creation and lookup rules
- PostgreSQL persistence with a database-generated ID
- Safe JSON errors for `400 Bad Request`, `404 Not Found`,
  `405 Method Not Allowed`, `415 Unsupported Media Type`, and
  `500 Internal Server Error`; empty `406 Not Acceptable` responses for unsupported response media types
- Unit, web-layer, persistence, and full API integration tests
- Flyway database migrations
- Guarded test database path for database-backed tests
- Maven and GitHub Actions verification

The server initializes every new Task with status `TODO`. Task collection results use bounded, zero-based pagination and deterministic ascending Task ID order. PATCH updates only a Task's status; PUT replaces its editable title, description, and status. Creating Evidence never changes Task fields and does not mean a Task is accepted or done. Client-controlled sorting, filtering, search, authentication, and deployment remain outside the current scope.

## Documentation

- [Architecture and evidence boundaries](docs/architecture.md)
- [Technology baseline decision](docs/adr/0001-technology-baseline.md)
- [Complete local API demo](docs/demo.md)
- [Task Evidence API](docs/task-evidence-api.md)
- [Task Evidence verification](docs/task-evidence-verification.md) and [regression index](docs/task-evidence-regression-index.tsv)
- [Task Evidence HTTP demo runbook](docs/task-evidence-http-demo-runbook.md), [operation spec](docs/task-evidence-http-demo-spec-v1.0.md), and [AI delivery case](docs/task-evidence-ai-delivery-case-v1.0.md)
- [Test database guard integration record](daily-log/testdb-guard-integration-2026-10-31.md), a dated log written before the first remote CI run

## Two separate local flows

| | Task CRUD demo | Task Evidence demo |
| --- | --- | --- |
| Guide | [docs/demo.md](docs/demo.md) and the setup below | [Runbook](docs/task-evidence-http-demo-runbook.md) |
| Entry | `compose.yaml`, `.env`, `mvn spring-boot:run` | `scripts/task-evidence-demo.sh` only |
| Database | Local PostgreSQL on `127.0.0.1` from the root compose file | Dedicated database on `127.0.0.1:55433`, with generated one-time credentials |
| Application port | `127.0.0.1:8080` | `127.0.0.1:18080` |

Do not mix the two: the Evidence demo does not use `.env` or the root `compose.yaml`. Its entry requires a clean, committed working tree and fixed local paths, so it does not run on arbitrary machines or clones. See the runbook for its preconditions and limits.

## API overview

| Operation | Endpoint | Successful response |
| --- | --- | --- |
| Create | `POST /api/tasks` | `201 Created`, Task body and `Location` |
| List | `GET /api/tasks?page=0&size=20` | `200 OK`, array and `X-Has-Next-Page` |
| Read | `GET /api/tasks/{id}` | `200 OK`, Task body |
| Update status | `PATCH /api/tasks/{id}/status` | `200 OK`, Task body |
| Replace editable fields | `PUT /api/tasks/{id}` | `200 OK`, Task body |
| Delete | `DELETE /api/tasks/{id}` | `204 No Content`, empty body |
| Create Evidence | `POST /api/tasks/{id}/evidence` | `201 Created`, Evidence body, no `Location` |
| List Evidence | `GET /api/tasks/{id}/evidence?page=0&size=20` | `200 OK`, array and `X-Has-Next-Page` |

See the endpoint sections below for validation, missing-resource behavior, replay semantics, and examples. Generated IDs must be taken from actual create responses; example IDs are illustrative. Evidence validation, ordering, and errors are documented in the [Task Evidence API](docs/task-evidence-api.md).

## Technology baseline

- Java 21
- Spring Boot 3.5.16
- Maven
- PostgreSQL 17.11
- Flyway
- Docker Compose
- GitHub Actions

## Package structure

```text
io.github.jiangzhengxun.taskmanager
├── configuration
├── evidence
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
│       └── persistence
└── task
    ├── api
    ├── application
    ├── domain
    └── infrastructure
        └── persistence
```

## Prerequisites

Install the following tools:

- Java 21
- Maven 3.6.3 or later
- Docker Engine
- Docker Compose

## Local setup

Clone the repository and enter its directory:

```bash
git clone https://github.com/Jiang-Zheng-Xun/java-task-manager-portfolio.git
cd java-task-manager-portfolio
```

Create the local environment file:

```bash
cp .env.example .env
```

Replace the placeholder in `.env` with a local-only password:

```dotenv
DB_PASSWORD=replace-with-a-local-password
```

The `.env` file is ignored by Git and must not be committed.

Start PostgreSQL:

```bash
docker compose up -d
docker compose ps
```

Load the local environment variables:

```bash
set -a
source .env
set +a
```

Start the application:

```bash
mvn spring-boot:run
```

A successful startup includes:

```text
Started JavaTaskManagerPortfolioApplication
```

The application listens on `127.0.0.1:8080` by default. The implemented endpoints are `POST /api/tasks`, `GET /api/tasks`, `GET /api/tasks/{id}`, `PATCH /api/tasks/{id}/status`, `PUT /api/tasks/{id}`, `DELETE /api/tasks/{id}`, `POST /api/tasks/{id}/evidence`, and `GET /api/tasks/{id}/evidence`; the root path is not an application endpoint.

Stop the application with `Ctrl+C`.

Stop the local PostgreSQL container when finished:

```bash
docker compose down
```

This setup runs the application only. `mvn verify` also needs the guarded test database settings; see [Test database guard](#test-database-guard).

## Create Task API

### Endpoint

```http
POST /api/tasks
Content-Type: application/json
```

The client supplies only `title` and optional `description`. The server generates `id`, initializes `status` to `TODO`, and sets both timestamps.

The endpoint returns `201 Created` with the complete created Task
representation. The `Location` header identifies the corresponding GET-by-ID resource as `/api/tasks/{createdId}`.

### Successful request

With PostgreSQL and the application running, execute:

```bash
curl --include \
  --request POST \
  --header "Content-Type: application/json" \
  --data '{
    "title": "Prepare portfolio README",
    "description": "Add API examples"
  }' \
  http://127.0.0.1:8080/api/tasks
```

Example response:

```http
HTTP/1.1 201 Created
Location: /api/tasks/1
Content-Type: application/json
```

```json
{
  "id": 1,
  "title": "Prepare portfolio README",
  "description": "Add API examples",
  "status": "TODO",
  "createdAt": "2026-09-21T13:30:00Z",
  "updatedAt": "2026-09-21T13:30:00Z"
}
```

The generated `id`, timestamps, and `Location` value vary between executions. The ID in `Location` matches the `id` in the response body.

### Validation rules

- `title` is required, must contain non-whitespace text, and must not exceed 200 characters.
- `description` is optional and must not exceed 2000 characters when present.
- Client-supplied fields such as `id` and `status` are rejected.
- Invalid requests do not create database rows.

### Blank-title example

```bash
curl --include \
  --request POST \
  --header "Content-Type: application/json" \
  --data '{
    "title": "   ",
    "description": "Add API examples"
  }' \
  http://127.0.0.1:8080/api/tasks
```

Example response:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json
```

```json
{
  "timestamp": "2026-09-21T13:31:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "title must not be blank",
  "path": "/api/tasks"
}
```

The timestamp varies between executions.

### Unsupported-field example

```bash
curl --include \
  --request POST \
  --header "Content-Type: application/json" \
  --data '{
    "title": "Prepare portfolio README",
    "status": "COMPLETED"
  }' \
  http://127.0.0.1:8080/api/tasks
```

This request returns `400 Bad Request`. Error responses use a stable public shape and do not expose stack traces, SQL, database endpoints, credentials, or internal class names.

## List Tasks API

### Endpoint and pagination

```http
GET /api/tasks?page=0&size=20
Accept: application/json
```

`page` starts at 0 and defaults to 0. `size` defaults to 20 and must be between 1 and 100. Omitting both parameters returns at most 20 Tasks. Results always use ascending Task ID order; client-controlled sorting, filtering, and search are not supported.

A successful response has status `200 OK`, a JSON array of complete Task representations, and the `X-Has-Next-Page` response header (`true` or `false`). The header indicates whether another page existed when this page was queried. Each Task contains `id`, `title`, `description`, `status`, `createdAt`, and `updatedAt`.

For example:

```bash
curl --include \
  'http://127.0.0.1:8080/api/tasks?page=0&size=2'
```

With more than two Tasks, the first page has this shape:

```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Has-Next-Page: true
```

```json
[
  {
    "id": 1,
    "title": "First task",
    "description": null,
    "status": "TODO",
    "createdAt": "2026-09-24T08:00:00Z",
    "updatedAt": "2026-09-24T08:00:00Z"
  },
  {
    "id": 2,
    "title": "Second task",
    "description": null,
    "status": "TODO",
    "createdAt": "2026-09-24T08:01:00Z",
    "updatedAt": "2026-09-24T08:01:00Z"
  }
]
```

Actual IDs, timestamps, values, and `X-Has-Next-Page` depend on the stored data. Request `page=1&size=2` to read the next page. An empty collection or a page beyond the available data returns `200 OK`, `[]`, and `X-Has-Next-Page: false`.

Invalid values, including negative `page`, `size=0`, `size>100`, and non-integer parameters, return a safe `400 Bad Request` response. The API does not provide `totalElements` or `totalPages`.

### Read path

```text
GET /api/tasks?page=...&size=...
→ ListTasksController (validate parameters, map response and header)
→ ListTasksUseCase / ListTasksService
→ TaskRepository output port
→ TaskRepositoryAdapter / ordered JPA Slice
→ PostgreSQL
→ TaskPage(List<Task>, hasNext)
→ List<TaskResponse> and X-Has-Next-Page
```

The persistence query enforces `id ASC` and fetches only a bounded page plus the information needed to determine `hasNext`. The adapter maps JPA entities to domain Tasks before returning to the application layer. The service coordinates the read operation in a read-only transaction; it does not sort the result.

Offset pagination can repeat or skip items if Tasks are inserted or deleted between separate page requests. This prototype does not promise a snapshot across multiple requests. Large page offsets may also be costly; this endpoint is not yet a production-scale pagination design.

## Get Task by ID API

### Endpoint

```http
GET /api/tasks/{id}
Accept: application/json
```

The ID must be a positive integer within the Java `long` range.

### Successful request

Use the `Location` returned by a successful create request:

```bash
curl --include \
  http://127.0.0.1:8080/api/tasks/1
```

Example response:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "id": 1,
  "title": "Prepare portfolio README",
  "description": "Add API examples",
  "status": "TODO",
  "createdAt": "2026-09-22T08:55:00Z",
  "updatedAt": "2026-09-22T08:55:00Z"
}
```

Replace `1` with the actual ID returned by the create request.

### Missing Task

A valid positive ID that does not exist returns:

```http
HTTP/1.1 404 Not Found
Content-Type: application/json
```

The response uses the standard error shape and does not expose
database or framework details.

### Invalid ID

Zero, negative, non-numeric, and out-of-range IDs return
`400 Bad Request`. Type-conversion details and internal class names are not exposed.

### Read-path architecture

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

`TaskNotFoundException` represents a missing resource in the
application layer. `GlobalExceptionHandler` maps that application
meaning to `404 Not Found` at the HTTP boundary.

## Update Task Status API

### Endpoint and request

```http
PATCH /api/tasks/{id}/status
Content-Type: application/json
```

```json
{"status":"IN_PROGRESS"}
```

Accepted statuses are `TODO`, `IN_PROGRESS`, and `COMPLETED`.

### Response and errors

A successful request returns `200 OK` with the complete Task representation. An actual status change preserves `id`, `title`, `description`, and `createdAt`, and updates `updatedAt`. Repeating the current status leaves the stored Task and `updatedAt` unchanged.

A non-positive or non-integer ID, missing or invalid status, malformed JSON, or unknown request field returns a safe `400 Bad Request`. A well-formed ID with no matching Task returns `404 Not Found`.

### Scope and method choice

This endpoint uses `PATCH` because it updates only `status`; it does not change `title` or `description`. For full replacement of all editable Task fields, use `PUT /api/tasks/{id}` under the contract below.

### Verified test evidence

Full-context MockMvc tests with PostgreSQL verify direct completion (`TODO` → `COMPLETED`) and reopening (`COMPLETED` → `IN_PROGRESS`), followed by GET requests. They verify that PATCH preserves the Task's identity, title, description, and creation time. This lifecycle test uses a rollback-managed test transaction; flush and clear do not prove commit.

A separate test disables the outer test transaction with
`Propagation.NOT_SUPPORTED`. After the PATCH service transaction completes, JDBC reads verify the committed status and preserved fields. Repeating the same status preserves the stored row and `updated_at` exactly. The test cleans up only the Task ID it created.

HTTP timestamps may retain nanoseconds, while PostgreSQL stores microsecond precision. Cross-boundary timestamp comparisons allow at most one microsecond; unchanged database values are compared exactly.

These tests use the full Spring context and MockMvc, not a live network server. JDBC observation occurs outside the original write transaction; the test does not require a different physical pooled connection.

## Replace Task API

### Endpoint and complete editable representation

```http
PUT /api/tasks/{id}
Content-Type: application/json
```

```json
{
  "title": "Prepare release notes",
  "description": null,
  "status": "COMPLETED"
}
```

The request must include all three editable fields: `title`, `description`, and
`status`. `description: null` clears the description; a blank description
normalizes to null. Omitting any field is invalid. The server controls `id`,
`createdAt`, and `updatedAt`; supplying them or any unknown field is rejected.

The existing title normalization and length rules apply. Status must be
`TODO`, `IN_PROGRESS`, or `COMPLETED`; the existing transition rules allow
direct completion and reopening.

### Response and errors

A successful replacement returns `200 OK` with the complete Task
representation. A changed Task keeps its `id` and `createdAt` and receives a
new `updatedAt`. Repeating an equivalent representation after normalization
preserves `updatedAt` and skips an unnecessary save.

Invalid IDs or requests return a safe `400 Bad Request`; a valid positive ID
without a matching Task returns a safe `404 Not Found`. Unexpected failures
return a safe `500 Internal Server Error`. Invalid requests do not change the
stored Task.

### PUT and PATCH

`PUT /api/tasks/{id}` replaces the complete set of editable fields under the contract above. `PATCH /api/tasks/{id}/status` changes only status and leaves title and description untouched. Both use the same existing status values and preserve `updatedAt` when the normalized resource state is unchanged.

## Delete Task API

### Endpoint

```http
DELETE /api/tasks/{id}
```

The ID must be a positive integer within the Java `long` range. Deleting an existing Task physically removes it and returns `204 No Content` with an empty response body.

```bash
curl --include --request DELETE \
  http://127.0.0.1:8080/api/tasks/1
```

A subsequent `GET /api/tasks/1` returns `404 Not Found`, and collection results no longer include the deleted Task. Deleting that ID again, or deleting another valid ID that does not exist, returns a safe `404`. Zero, negative, nonnumeric, and out-of-range IDs return a safe `400`. Unexpected failures return a safe `500` without exposing internal details.

Repeated deletion leaves the resource absent even though the second response is `404`. The endpoint does not implement soft deletion or batch deletion.

## HTTP error boundaries

The API preserves HTTP protocol errors instead of treating them as unexpected server failures.

| Request | Response |
| --- | --- |
| `PUT /api/tasks` with `Accept: application/json` | `405 Method Not Allowed`, safe JSON error and the `Allow` header |
| `POST /api/tasks` with `Content-Type: text/plain` and `Accept: application/json` | `415 Unsupported Media Type`, safe JSON error |
| `GET /api/tasks` with `Accept: application/xml` | `406 Not Acceptable`, empty body |

The 405 and 415 responses use the existing five-field error shape:
`timestamp`, `status`, `error`, `message`, and `path`. Their messages are `HTTP method is not supported` and `Request Content-Type is not supported`. Spring-provided protocol headers are preserved. The API does not force a JSON error body when the requested response media type cannot be produced.

WebMvc tests verify that the representative 405 and 415 requests do not invoke the use cases. Full-context integration tests verify their status, headers, safe response bodies, and unchanged database row counts. Both test layers verify the representative 406 response. These tests do not claim that response negotiation always occurs before business logic executes.

## Database schema and reliability

Flyway applies versioned migrations from:

```text
src/main/resources/db/migration
```

`V1__create_tasks_table.sql` creates the `tasks` table. The database generates its primary-key `id`. It limits `title` to 200 characters and requires a non-null, non-blank title; `description` is nullable and limited to 2000 characters. `status` is non-null and limited to `TODO`, `IN_PROGRESS`, or `COMPLETED`. Both timestamps are non-null. JPA uses `ddl-auto: validate`, so it checks the mapped schema rather than creating or updating tables.

The ID primary key provides a unique B-tree index. The implemented list query orders bounded pages by ascending ID; there is currently no status- or timestamp-filtered query requiring another index. No additional migration or index was needed for this reliability review.

`V2__create_task_evidence_table.sql` creates the `task_evidence` table, with a cascading foreign key to `tasks` and a composite index on task, creation time, and ID. See the [Task Evidence API schema notes](docs/task-evidence-api.md#schema-migration-v2).

`TaskDatabaseConstraintIntegrationTest` writes directly through JDBC to PostgreSQL. It verifies a valid row at the title and description length limits, and checks that PostgreSQL rejects blank or null titles, invalid or null statuses, null timestamps, and values exceeding column lengths. These tests exercise database enforcement independently of HTTP request validation and domain normalization. The successful test deletes its own row; rejected inserts do not leave rows behind.

## Test database guard

Database-backed Spring tests carry `@GuardedTestDatabase`. Before migration and JPA startup, the guard checks the resolved datasource URL and username against an independent policy, rejects datasource, JPA, and Flyway definitions outside the controlled path, and verifies the connected database name, roles, and role privileges. A rejected target stops the test context.

`TEST_DB_MODE` selects the policy and accepts only `local` or `ci`:

| Mode | Policy | Used by |
| --- | --- | --- |
| `ci` | Fixed in `TestDatabasePolicyProvider`: `task_manager_test` on `localhost:5432` with role `jtm_test`; a policy file is rejected | GitHub Actions |
| `local` | A private properties file named by `TEST_DB_POLICY_FILE`, kept outside the repository and holding no password | Local runs |

Local database-backed runs need a dedicated test database and a restricted login role: no superuser, create-database, create-role, replication, or bypass-RLS attribute, and no role membership. The root `compose.yaml` role is the image's superuser, so that database does not satisfy the policy, and this repository provides no local provisioning script. Without a valid mode and policy, database-backed tests fail closed.

## Continuous integration

The GitHub Actions workflow runs Maven verification with:

- Eclipse Temurin Java 21
- A job-local PostgreSQL 17.11 container, created and removed by `.github/scripts/testdb-ci.sh`, not a workflow service container
- The guarded test database mode (`TEST_DB_MODE=ci`)
- Read-only repository content permission
- Database credentials generated inside the job, kept in private files and passed through stdin and the process environment

CI executes:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Maven output is captured first. It is displayed, and test reports are prepared as an artifact, only after a scan for the job's generated secrets passes. The scan does not turn a Maven failure into success, and it is not a guarantee that nothing leaked.

## Security baseline

- The repository is public; the project is not a publicly deployed service.
- Public repository content is separate from private operational environments;
  local environment files, credentials, database contents, and scan reports
  are not publication artifacts.
- PostgreSQL is bound to `127.0.0.1` for local development.
- Local Task CRUD setup: real credentials are stored only in the ignored `.env` file, and `.env.example` contains placeholders only.
- Task Evidence demo: it does not use `.env`. Its entry generates one-time credentials for each run; see the [runbook](docs/task-evidence-http-demo-runbook.md).
- CI: tests use a job-local PostgreSQL container. Its credentials are generated inside the job, kept in private files, and passed through stdin and the process environment; no CI database password is stored in the repository. The Maven log and Surefire reports are scanned for those generated secrets before they are displayed or prepared as an artifact. A clean scan does not turn a Maven failure into success, and it is not a guarantee that nothing leaked.
