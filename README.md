# Java Task Manager Portfolio

A verifiable backend portfolio project built with Java 21, Spring Boot 3.5.16, Maven, PostgreSQL 17, Flyway, and GitHub Actions.

## Current scope

The current prototype provides executable Task creation and read vertical slices:

- `POST /api/tasks`
- `GET /api/tasks`
- `GET /api/tasks/{id}`
- Request validation and strict rejection of unsupported fields
- Domain-level creation and lookup rules
- PostgreSQL persistence with a database-generated ID
- Safe `400 Bad Request`, `404 Not Found`, and
  `500 Internal Server Error` responses
- Unit, web-layer, persistence, and full API integration tests
- Flyway database migration
- Maven and GitHub Actions verification

The server initializes every new Task with status `TODO`. Collection results use deterministic ascending Task ID order. Pagination, client-controlled sorting, filtering, search, update, completion, deletion, authentication, and deployment remain outside the current scope.

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

Run the complete local verification:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Start the application:

```bash
mvn spring-boot:run
```

A successful startup includes:

```text
Started JavaTaskManagerPortfolioApplication
```

The application listens on `127.0.0.1:8080` by default. The implemented endpoints are `POST /api/tasks`, `GET /api/tasks`, and `GET /api/tasks/{id}`; the root path is not an application endpoint.

Stop the application with `Ctrl+C`.

Stop the local PostgreSQL container when finished:

```bash
docker compose down
```

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

### Endpoint

```http
GET /api/tasks
Accept: application/json
```

The endpoint returns `200 OK` with a JSON array of complete Task representations. Results use deterministic ascending Task ID order.

Pagination, client-controlled sorting, filtering, and search are not supported in the current scope.

### Successful request

With PostgreSQL and the application running, execute:

```bash
curl --include \
  http://127.0.0.1:8080/api/tasks
```

Example response:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
[
  {
    "id": 1,
    "title": "Prepare portfolio README",
    "description": "Add API examples",
    "status": "TODO",
    "createdAt": "2026-09-23T08:00:00Z",
    "updatedAt": "2026-09-23T08:00:00Z"
  },
  {
    "id": 2,
    "title": "Verify collection endpoint",
    "description": null,
    "status": "TODO",
    "createdAt": "2026-09-23T08:05:00Z",
    "updatedAt": "2026-09-23T08:05:00Z"
  }
]
```

Generated IDs, timestamps, and stored Task values vary between executions.

### Empty collection

An empty collection is a successful result and returns:

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
[]
```

### Collection read-path architecture

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

The persistence adapter retrieves rows in ascending ID order and maps each `TaskEntity` to a domain `Task`. The controller maps the domain collection to API `TaskResponse` objects. An empty repository result flows through the same path and becomes an empty JSON array.

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

## Database migration

Flyway automatically applies migrations from:

```text
src/main/resources/db/migration
```

The initial migration creates the `tasks` table and its baseline constraints.

## Continuous integration

The GitHub Actions workflow runs Maven verification with:

- Eclipse Temurin Java 21
- PostgreSQL 17.11 service container
- Read-only repository content permission
- CI-only database credentials

CI executes:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

## Security baseline

- The repository is private during active development.
- PostgreSQL is bound to `127.0.0.1` for local development.
- Real credentials are stored only in the ignored `.env` file.
- `.env.example` contains placeholders only.
- CI uses an explicit test-only database password.
