# Day 11 — PATCH lifecycle and committed-state evidence

Date: 2026-10-02
Mode: Light

## Goal

Close the verified evidence gaps for PATCH completion/reopening and committed-state visibility without changing the existing API contract.

## Verified outcomes

- Added a full-context MockMvc/PostgreSQL lifecycle test:
  POST → PATCH COMPLETED → GET → PATCH IN_PROGRESS → GET.
- Verified that PATCH preserves identity, title, description, and creation time across completion and reopening.
- Added a separate test with `Propagation.NOT_SUPPORTED` to disable the outer test transaction.
- After PATCH completes, JDBC reads observe committed status and preserved fields outside the original write transaction.
- Same-status replay preserves the stored row and `updated_at` exactly.
- The committed-state test cleans up only the Task ID it creates.
- README documents these evidence boundaries.

## Validation evidence

User-reported local results:

- Pre-change full regression: 121 tests, zero failures/errors/skipped, BUILD SUCCESS.
- Each new method passed its targeted run.
- Updated `TaskApiIntegrationTest`: 21 tests, zero failures/errors/skipped, BUILD SUCCESS.
- `git diff --check` passed.

Targeted runs overlap with the class-level run and are not added together.

## Evidence boundaries

- The lifecycle test uses a rollback-managed test transaction.
  Flush and clear do not prove commit.
- The committed-state test removes the outer test transaction and observes the result through JDBC after the service transaction completes.
- It does not require a distinct physical pooled connection.
- These are full Spring context/MockMvc tests with PostgreSQL, not live network-server tests.
- Cross-boundary timestamp comparisons allow at most one microsecond. Unchanged database values are compared exactly.

## Reviewed Reflection

### Technical learning

Flush synchronizes SQL but is not commit. Disabling the outer test
transaction allows the service transaction to complete before observation.

Timestamp assertions must follow the value's source. A newly generated HTTP timestamp may retain nanoseconds, while PostgreSQL stores microseconds. A same-status replay reads the persisted timestamp and preserves it.

### Problems and resolutions

- An unquoted Maven method selector containing `#` was rejected by zsh before Maven executed. Method selectors are now quoted.
- The initial lifecycle assertion compared creation timestamps exactly across HTTP and database precision and failed on a 168 ns difference. PATCH and GET creation-time comparisons now use the existing one microsecond tolerance.
- These corrections affected test assertions and commands; production code was not changed.

### Process improvements

- Check timestamp sources before selecting equality or tolerance.
- Supply quoted Maven method selectors.
- Review complete diffs and synchronize verified Issue gates, then read them back before reporting checklist completion.

### Question Bank candidates

Review the existing flush/commit and HTTP/database timestamp questions for possible evidence updates. Implementation evidence alone does not raise Mastery.

## CI reproducibility review

The reviewed workflow uses Java 21, PostgreSQL 17.11-alpine with a health check, and the same Maven clean verify command used locally. CI configuration was retained. This does not claim that the runner, Maven, and all tooling are fully pinned.

## Security and scope

Level 1/2 review found no added dependencies, migrations, permissions, or network exposure. No secrets were included in the reviewed changes. Committed-test cleanup is limited to its own Task ID.

The work adds evidence for the existing PATCH contract. It does not add authentication, deployment, new API behavior, or schema changes.
