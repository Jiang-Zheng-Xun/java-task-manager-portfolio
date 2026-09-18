# Java Task Manager Portfolio

A verifiable backend portfolio project built with Java 21, Spring Boot 3.5.16, Maven, PostgreSQL 17, Flyway, and GitHub Actions.

## Current scope

The Day 1 baseline provides:

- Spring Boot application skeleton
- Layered package boundaries
- PostgreSQL development environment
- Flyway database migration
- Spring application context test
- Maven verification workflow
- GitHub Actions CI baseline

Task management API endpoints have not been implemented yet.

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

The application listens on `127.0.0.1:8080` by default. The root path currently returns `404` because no API endpoint has been implemented yet.

Stop the application with `Ctrl+C`.

Stop the local PostgreSQL container when finished:

```bash
docker compose down
```

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
