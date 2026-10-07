# Day 5 — Bounded Task Collection Pagination

**Date:** 2026-09-24
**Issue:** #10
**Base branch:** `develop`
**Feature branch:** `feature/day-5-bounded-pagination`

## Goal

Bound `GET /api/tasks` results while preserving the JSON array response, deterministic ascending Task ID order, safe validation, and clear evidence of whether another page exists.

## Delivered

- Added zero-based `page` and bounded `size` parameters. Defaults are `page=0` and `size=20`; accepted `size` is 1–100.
- Preserved `200 OK` with a `List<TaskResponse>` JSON array.
- Added `X-Has-Next-Page: true|false`.
- Empty and beyond-last pages return `200 OK`, `[]`, and `X-Has-Next-Page: false`.
- Invalid numeric ranges or non-integer parameters return a safe `400 Bad Request`.
- Kept fixed `id ASC` ordering; did not add client sorting, filtering, search, total count, or total pages.
- Added `TaskPage` to carry domain Tasks and `hasNext` through the application boundary.
- Used an ordered Spring Data JPA `Slice` in persistence; mapped entities to domain Tasks in the adapter.
- Removed the previous unbounded collection read methods after the paginated path was verified.
- Updated README with parameters, examples, response header, architecture, validation, and offset-pagination limitations.

## Contract and architecture

```text
GET /api/tasks?page=...&size=...
→ ListTasksController
→ ListTasksUseCase / ListTasksService
→ TaskRepository
→ TaskRepositoryAdapter
→ ordered JPA Slice / PostgreSQL
→ TaskPage(List<Task>, hasNext)
→ List<TaskResponse> + X-Has-Next-Page
```

The controller validates public parameter bounds and maps the result to the HTTP response. The service coordinates the read-only use case without sorting. Persistence guarantees `id ASC` and determines `hasNext`; JPA types stay inside the adapter. A large `page * size` offset is checked before passing it to JPA. This offset check is not a total-row count.

## Verification evidence

- Pre-change baseline: 48 tests; 0 failures, errors, or skipped; `BUILD SUCCESS`.
- Repository integration: 6 tests, including empty, ordered, partial, last, and beyond-last pages.
- Application service: 2 tests for parameter delegation and return of the repository result.
- WebMvc: 9 tests for JSON array, defaults, `X-Has-Next-Page`, safe error handling, and invalid parameters rejected before the use case.
- Full PostgreSQL API integration: 11 tests, including POST three Tasks followed by paginated GET, default limit 20 with 21 Tasks, and accepted `size=100`.
- Full pre-delivery `mvn clean verify`: **57 tests**, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.
- `git diff --check`: passed.
- Localhost curl: verified JSON array, response header, and safe `400` for `size=101`. The existing database contained one Task, so this manual result alone does not prove multi-row ordering or `hasNext=true`; integration tests provide those claims.
- Changed-file review included `git status --short`, so the new `TaskPage.java` was not omitted.

## Security and limitations

Level 1 and Level 2 checks passed. `.env` remains ignored; no real secret or connection value was added. The application and PostgreSQL were used on localhost. Invalid parameters receive safe responses, the result size is bounded, and JPA entities do not cross the persistence boundary.

Offset pagination does not guarantee a stable snapshot across separate requests when Tasks are inserted or deleted. Large offsets may be costly. The prototype does not yet provide a production-scale pagination design or total-count metadata.

## Learning and Reflection

`hasNext` originates from the persistence `Slice`, passes through `TaskPage`, and becomes an HTTP header. The offset calculation checks a JPA-supported position, not the number of Tasks. The service unit test with a mock repository, WebMvc test with a mock use case, full PostgreSQL API test, and single-row manual curl each prove different boundaries.

An intermediate Controller change left two `@GetMapping` methods for the same path; Spring reported an ambiguous mapping. Removing the old method restored the WebMvc tests. Future edit instructions should explicitly say whether to add or replace a whole method and include a direct `code` path. At each completed gate, revisit the Issue checklist promptly.

The `Slice`/`hasNext` data flow and the distinction between WebMvc and service unit test evidence remain **Review**. Question Bank candidates require deduplication and review before any upsert.
