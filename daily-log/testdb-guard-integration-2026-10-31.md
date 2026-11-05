# Test database guard integration — 2026-10-31

Issue #39, items I2-E to I2-G, and the I1 delivery. This log records what is accepted, what is only prepared, and what
is still open. **I2-G (remote CI) has not run.** Nothing here has been committed, pushed, reviewed as a PR or merged.
The I1 delivery package awaits independent review and owner acceptance.

## Purpose and lifecycle

`@GuardedTestDatabase` makes a Spring test refuse to touch a database unless the target is the expected test database
and the connecting role is the expected restricted role. For each test context, the order is:

1. **Settings** — `GuardedTestDatabaseInitializer` resolves the policy for `TEST_DB_MODE` once, adds fixed driver
   timeouts and checks the resolved datasource URL and username against the policy before refresh.
2. **Bean definitions** — `TestDatabaseBeanDefinitionGuard` rejects datasource, JPA and Flyway definitions that could
   connect outside the controlled path.
3. **Identity and cleanup** — `GuardedFlywayMigrationStrategy` asks `TestDatabaseIdentityVerifier` to check database
   name, login, effective and owner roles, login ability, privilege flags and role memberships through the DataSource
   Flyway uses, and to release every JDBC resource.
4. **Migration** — `Flyway#migrate()` runs only after step 3 returned.
5. **JPA** — the entity manager factory is created after migration.

## Independent policies

- `TEST_DB_MODE=local` reads a private properties file outside the repository. That file is not part of delivery.
- `TEST_DB_MODE=ci` uses a policy fixed in `TestDatabasePolicyProvider`:
  `jdbc:postgresql://localhost:5432/task_manager_test`, database `task_manager_test`, and role `jtm_test` as login,
  effective and owner role. A policy file is rejected in this mode.
- Neither policy is derived from the datasource under test or from database responses.

## Verified outcomes (local evidence)

| Item | Result | Evidence |
|---|---|---|
| I2-E | Accepted. Guarded startup ran identity, migration and JPA in that order; identity rejection and cleanup-failure controls stopped before migration. | BOOT28 (ordering), BOOT29 (two controls). |
| I2-F | Accepted. WebMvc/unit regression and the four DB entry points passed with their original MOCK / DataJpaTest setups. | BOOT31: R0 82, R1 1, R2 9, R3 10, R4 21 tests, all 0 failures/errors/skips. Package SHA-256 `502b12bbf260eb36c59f230daafeae111fd30940620084ef3607b40fa27a83b4`. |
| L1 rehearsal | Accepted. The 40 non-DB test-pattern classes ran under the CI-mode environment without DB or password: 509 tests, 0 failures, 0 errors, 40 skipped (469 actually executed). The skips are the control-gated tests. | BOOT33 call 1. |

L1 is a rehearsal only. It did not run the workflow, the CI helper scripts, provisioning or the four DB entry points.

## Stops and corrections

- **BOOT28 STOP.** Two identity controls were selected with truncated method names, so they did not run. BOOT29 reran
  them with exact names and a per-row checker, and both passed.
- **BOOT30 STOP.** No Maven ran. An auxiliary tool defect used the single recovery cycle; a second one (hashing a cache
  directory) ended the batch. The first BOOT30 bootstrap command never executed: its approval prompt timed out (as the
  owner reported), and the exact fragment that triggered the prompt is not known.
- **BOOT33 STOP.** L1 ran (call 1) *before* the complete static review of the CI files. Shell-helper corrections made
  afterwards were therefore never exercised by L1. Call 2 started 20:56:04 CST, so its effective deadline was
  21:06:04 CST (not the owner deadline, as first reported); packaging at 20:57:44 was within it. Call 2 stopped because
  my intended-file check command failed on a valid result, and the recovery cycle was already used.
- **BOOT34 STOP.** The call expired at 21:17:19 CST during a usage-limit interruption. A correction to
  `testdb-ci.sh` (root-owned admin password file, no uid lookup) was left in the working tree without hash, syntax
  check or diff record; its exact edit time is unproven. BOOT35 recorded it as a current-state observation and reviewed
  it.

## CI implementation (prepared, not yet run)

- `.github/workflows/ci.yml` keeps the `pull_request`/`push` triggers for `develop` and `main`, `contents: read`,
  Java 21 and `mvn clean verify`.
- `.github/scripts/testdb-ci.sh` creates (without starting) PostgreSQL `17.11-alpine` pinned by image digest under a
  per-run container name, generates separate admin and application secrets in the job, copies the admin secret into
  the stopped container as a root-owned 0400 file (no host mount), starts it, creates `jtm_test` (LOGIN, no superuser,
  createdb, createrole, replication or bypass-RLS, NOINHERIT) and `task_manager_test` owned by it, and checks the
  resulting flags against fixed expected values. Admin access happens only in setup, through the container's local
  socket.
- `.github/scripts/testdb-ci-report.py` scans captured Maven output and Surefire reports for the generated secrets
  before anything is shown or uploaded. If a scan fails or finds a hit, nothing is shown or uploaded. Reports from a
  failing run are labelled `failure-evidence`, and the Maven status is never turned into success.
- Cleanup always runs. It removes only this job's container (checked by ID and name) and its private directory.

## Learning, problems and process improvements

- Expected results were fixed from source before every run, and Maven's exit code alone was never treated as a pass.
  This caught the BOOT28 selection error.
- Most stops came from my own helper tools (escaping, cache files, substring matches, empty-match `grep` pipelines),
  not from the code under test. Explicit set/count comparisons, `ast.parse` instead of `py_compile`, hashing explicit
  file lists and one batch identifier for archive names now address the defects seen so far.
- Static review should come before any execution that depends on it. In BOOT33 it did not.
- Batches ran out of time on careful but slow preparation, and one expired during a usage-limit interruption. Smaller,
  pre-reviewed tools and earlier checkpoints would leave more time for the actual checks.
- Official sources settled one design question (when the image reads `POSTGRES_PASSWORD_FILE`). That source is
  current upstream, not proven to match the pinned digest.

## Reusable question candidates

- What exact result proves this row passed, and was it written down before the run?
- Does this check pass in the valid case, including when a filter matches nothing?
- Which later changes were not exercised by the evidence I am citing?
- Is this source the same version as the artifact we pin?
- Which deadline applies to this call, computed from its actual start?

## Limits and open items

- **I2-G is pending:** the remote CI run, its commit SHA and run identity, and acceptance of its reports.
- Functional assumptions for the first remote run: `docker cp` keeps the 0400/0700 modes from the archive; `/run`
  exists in the image; the pinned digest's entrypoint reads the password file as root like current upstream;
  port 5432 is free on the runner. If any of these fails, setup fails closed, which does not show that it works.
- With the image's local socket trust, anyone who can `docker exec` on the runner can act as superuser.
- A failed provisioning statement may appear in the container's server log. That log is never displayed, and the
  container is removed at cleanup.
- Sequences advance and locks are taken during tests. An interrupted committed test may leave rows behind.
- Same-user `/proc` environment visibility of the application password is accepted.
- Not done yet: commit, push, PR, CI, review, merge, branch cleanup and Issue closure.

The owner's rest interval was 21:05:26–21:31:40 CST; it excludes owner work time and did not extend any deadline.
