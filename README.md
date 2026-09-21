# Java Task Manager Portfolio

A verifiable backend portfolio project built with Java 21, Spring Boot 3.5.16, Maven, PostgreSQL 17, Flyway, and GitHub Actions.

## Current scope

The current prototype provides the first executable Task creation vertical slice:

- `POST /api/tasks`
- Request validation and strict rejection of unsupported fields
- Domain-level creation invariants
- PostgreSQL persistence with a database-generated ID
- Safe `400 Bad Request` and `500 Internal Server Error` responses
- Unit, web-layer, persistence, and full API integration tests
- Flyway database migration
- Maven and GitHub Actions verification

The server initializes every new Task with status `TODO`. GET, update, completion, deletion, authentication, and deployment remain outside the current scope.

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

The application listens on `127.0.0.1:8080` by default. The implemented endpoint is `POST /api/tasks`; the root path is not an application endpoint.

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
representation. It intentionally does not return a `Location` header because a corresponding GET-by-ID resource contract has not yet been implemented.

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

The generated `id` and timestamps vary between executions.

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
