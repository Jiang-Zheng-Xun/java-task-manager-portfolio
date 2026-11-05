# C1 — Exact TEST_DB_MODE parsing and JUnit contract tests

Date: 2026-10-23
Issue: #31
Base: 7cafa48a65eef903ced405a827f1266db920f3f9

## Scope

Introduce a test-support parser that accepts exactly `local` and `ci`.
All other inputs, including null, throw IllegalArgumentException with
the fixed message `CONFIG: invalid TEST_DB_MODE`.

The component does not read environment variables, configure Spring,
determine whether a database is required, validate database identity,
or protect database connections.

## Implementation

Three new files under:
src/test/java/io/github/jiangzhengxun/taskmanager/support/testdb/

- TestDatabaseMode.java: 22 lines; exact parsing.
- TestDatabaseModeSelfTest.java: 61 lines; standalone main-method harness.
- TestDatabaseModeTest.java: 59 lines; JUnit Jupiter contract tests.

The two previously accepted C1 files were preserved unchanged.
The JUnit test directly invokes parse and independently lists its inputs
and expected results. It uses printable case labels for control characters.

No production source, POM, dependency declaration, schema or CI workflow
was changed in this implementation.

## Validation evidence

Claude produced the bounded patches; ChatGPT independently reviewed them.
The user ran controlled integration and validation in an external,
fixed-base repository copy.

Reported and accepted results:
- Java21 compilation and standalone self-test passed.
- Standalone stdout was exactly MODE_SELF_TEST_OK followed by newline.
- Targeted JUnit: 20 tests, 0 failures, 0 errors, 0 skipped.
- All three source SHA-256 values matched the reviewed contents.
- The original working tree remained unchanged.
- No Docker or database startup occurred in this validation batch.

Source SHA-256:
- TestDatabaseMode.java:
  02b0051e6e55fdfc606fce9339603b98812c5c83007816c053084c90ab2430ce
- TestDatabaseModeSelfTest.java:
  79b930b57ea325e853021db44352bc66bdc3744de3bf6bd7b22db3f901388d98
- TestDatabaseModeTest.java:
  7f9c31b874081d98d6e5bf8a072187e99c0525ce22a321e7f8b2b468defe703f

The assistant inspected the supplied verification summary, not a direct
execution of the user's WSL tests. The standalone and JUnit suites cover
the same 20 contract cases; they are not 40 distinct behaviors.
The historical 123-test regression was not rerun in this validation.

## Reflection

Verified outcome:
A small no-tool model delivery was independently reviewed and integrated
into an isolated copy with executable JUnit coverage.

Technical learning:
A main-method self-test is not automatically a JUnit test.
Local validation tied to base and source hashes is distinct from
validation tied to a committed PR head.

Problems and resolutions:
Eight Zone.Identifier companion files caused package inventory validation
to stop. They were preserved outside the package with matching before/after
hashes; package validation and all 12 offline Python checks then passed.
Those Python checks validate preparation controls, not Java behavior.

Process corrections:
Usage of 0% was initially misread as 0% remaining; the interpretation was
corrected. The first two-hour work batch exceeded its limit by 4m23s,
which remains recorded rather than silently treated as approved.
These errors and manual handoffs remain process costs.

Improvement:
Reuse accepted outputs, bind source identities, distinguish generation,
review, execution and human acceptance, and stop on a failed gate.
No comparable baseline yet supports a claim of improved efficiency.

Reusable learning candidate:
Explain how standalone checks, parameterized JUnit cases, PR CI and
post-merge verification provide different evidence. No Question Bank
update or independent mastery assessment is implied.

## Boundaries

This is component-level validation, not complete TEST_DB_MODE guard,
Spring lifecycle integration, database-safety verification or full
pipeline acceptance.

Private run records, authorization files, local operational paths and
raw CLI logs remain outside the repository.

Dynamic PR/CI, user acceptance, merge, cleanup, final work time and
Issue-closure facts are maintained in Issue #31 and authorized closure
records rather than retrofitted into this stable log.
