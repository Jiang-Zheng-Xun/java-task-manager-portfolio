# Day 9 — Flyway V1 constraints and PostgreSQL reliability

**Date:** 2026-09-30
**Issue:** #18
**Base branch:** `develop` at `e563b67c2cf3c424a8a174bf0e9fa9a175afb8ba`
**Feature branch:** `feature/day-9-database-reliability`
**Planned mode:** Normal, maximum 7 effective hours

## Goal and scope

Verify the existing Flyway V1 schema and PostgreSQL constraints using direct database evidence. No V2 migration, additional index, schema change, timestamp ordering rule, deployment, or Day 11 PATCH evidence work was included.

## Verified schema and query

- The running database's Flyway history contains one successful V1 migration.
- The `tasks` columns, lengths, and nullability match
  `V1__create_tasks_table.sql`.
- PostgreSQL reports the `tasks_pkey` primary key, the non-blank title CHECK, and the allowed-status CHECK.
- `tasks_pkey` is the sole index reported for `tasks`. The implemented collection query uses bounded pages ordered by ascending ID; there is no implemented status- or timestamp-filtered query requiring an extra index.
- JPA uses `ddl-auto: validate` while Flyway manages the schema.

## Delivered tests and documentation

- Added `TaskDatabaseConstraintIntegrationTest` with nine direct JDBC tests against PostgreSQL. The valid case writes and reads maximum-length title and description values, then removes its own row.
- Rejected inserts verify blank and null titles, invalid and null statuses, null timestamps, and title or description values beyond column limits. SQLSTATE distinguishes CHECK, NOT NULL, and length violations.
- The test class does not use a test transaction. Failed inserts are separate statements and do not leave rows behind.
- README now describes the existing schema, its database enforcement, and the reason no new migration or index was added.
- Targeted database constraint tests: 9 passed, 0 failures/errors/skipped. Complete local `mvn clean verify`: 115 passed, 0 failures/errors/skipped, BUILD SUCCESS. The pre-change baseline was 106 tests.

## Evidence boundaries and security

The direct JDBC tests verify enforcement by the running PostgreSQL database, independently of API request validation and domain normalization. They do not prove a new HTTP contract or a separate observer transaction for an update. The Flyway history, catalog constraints, and index were also inspected through a read-only PostgreSQL transaction.

Level 1 and Level 2 review covered the database test inputs, documentation, existing migration, dependencies, ignored local credentials, and localhost database binding. No dependency, migration, permission, or network exposure change was part of this delivery. `git diff --check` passed.

## Reflection

API and domain validation do not substitute for testing the database integrity boundary. PostgreSQL SQLSTATE identifies whether a direct write failed because of a CHECK, NOT NULL, or column-length rule. An index should follow a measured or implemented query need; the existing ID primary key supports the current ID-ordered access path.

No implementation or test failure occurred. A repeated report of the same targeted nine tests was not counted as another set of tests. The pasted README diff showed only its opening lines, so the README section and Daily Log were inspected in full before freezing.

Inspecting the applied schema, actual query, and existing tests before proposing a migration or index kept the work tied to evidence. Review newly added files in the staged diff as well as modified tracked files.

Potential Question Bank candidates are the evidence difference between API validation and database constraints, and the use of PostgreSQL SQLSTATE to distinguish integrity failures. They require deduplication and review before any shared Question Bank write.
