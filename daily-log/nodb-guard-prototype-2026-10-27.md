# NoDB guard prototype and Binder compatibility correction — 2026-10-27

## Scope and outcome

Prepared five test-only support files for an explicitly supplied database
target policy, configuration inspection before context refresh, and bean
definition checks for a narrowly reviewed Boot auto-configuration path.

The owner accepted the two-file Binder correction and its specified
offline test evidence. This acceptance does not establish protection of
the application's existing database tests.

Base: `6f07a86cebb3ac0b7ff2afae08ed937fa03e50ce`.
Tracking: Issue #35.

## Design

The initializer reuses the existing exact local/ci mode and configuration
validator contracts. The policy receives expected targets independently
of resolved settings and stores no password.

Configuration inspection rejects unsupported DataSource, Hikari and
Flyway settings, including alternate connection paths. Unverifiable
property sources are rejected. External runtime exception messages are
replaced by fixed diagnostics.

An ordinary programmatic BeanFactoryPostProcessor checks the reviewed
Boot structure and rejects unsupported FactoryBeans and unresolved or
overly broad predicted types.

A narrow exception recognizes Boot's configuration-properties Binder
factory by its exact bean name, class and reviewed registration structure.
It does not request or actively instantiate the factory. Other scans and
structural checks still apply.

The exception is tied to the reviewed Boot 3.5.16 registration structure.
It does not authenticate who registered an otherwise identical definition.

## Verification evidence

Claude executed one specified offline Maven invocation:

`mvn -o -Dtest=TestDatabaseGuardLifecycleTest,TestDatabaseConfigValidatorTest,TestDatabaseModeTest test`

| Test class | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| TestDatabaseGuardLifecycleTest | 88 | 0 | 0 | 0 |
| TestDatabaseConfigValidatorTest | 29 | 0 | 0 | 0 |
| TestDatabaseModeTest | 20 | 0 | 0 | 0 |
| Total | 137 | 0 | 0 | 0 |

The run finished with exit code 0 and BUILD SUCCESS. ChatGPT independently
read the supplied log, correction diff, patch, hash manifests and Boot
bytecode evidence; ChatGPT did not execute these tests.

The delivered patch reproduces the five source hashes. The three support
files outside the correction scope retain their approved hashes.
Claude's integrity checks report that all 94 existing tracked files
remain unchanged.

Earlier results remain separate: the accepted fixture correction had
99 passing tests; subsequent hardening produced 132 tests with 19
failures. The current result does not erase those failures or add their
test counts to this run.

## Evidence limits

The lifecycle tests use synthetic property sources and stop refresh at
a fixture stage. Their counting driver and fake migration action are
fixture observers, not native PostgreSQL JDBC or Flyway interception.

The 17 historical FactoryBean-related failures have per-case static
attribution supported by the corrected outcomes. Their original logs
did not record the rejected bean identities.

Boot's property-source adapter can suppress a query exception and return
null. The corrected cases assert the observed fail-closed result and
redaction rather than an exception category the adapter does not expose.

This work does not prove that the Binder's complete construction chain
is connection-free or that arbitrary early custom code is intercepted.

## Reflection

### Technical learning

Framework infrastructure must be inspected before applying a universal
FactoryBean rejection rule. A compatibility exception should match the
specific reviewed definition and preserve all remaining checks.

Assertions must follow the framework's actual exception-handling path.
Fixed diagnostics and fail-closed behavior remain necessary when an
adapter suppresses the original exception.

### Problems and process corrections

The earlier environment-variable fixture required a Boot-recognized
property-source name and a Binder precondition assertion.

During this correction batch, patch generation encountered an error and
Claude continued rebuilding the artifact instead of stopping. The final
patch was hash-verified, but the continuation remains a process deviation.
The original failure command and error output were not available to the
independent reviewer at acceptance; the owner accepted retaining this
evidence gap without reproducing the failure.

Historical reconstructed source copies remain labeled as reconstructed.
They are not represented as untouched original backups.

Future work should stop on artifact-generation failures, preserve the
command and error output, and obtain a decision before recovery.

### Reusable learning candidates

- Narrow framework-infrastructure exceptions without active instantiation.
- Testing fail-closed behavior when adapters suppress exceptions.
- Distinguishing fixture counters from native connection evidence.

These are candidates only; no Question Bank update or Mastery increase
is claimed.

## Remaining work

The four existing database test entry points have not been connected to
this prototype. Native connection/migration observation, JPA integration,
dedicated-environment recovery checks and CI integration remain separate.

The full guard, Evidence API and collaboration-efficiency comparison are
not completed by this delivery. No efficiency improvement is claimed
without a comparison baseline.

Private environment configuration and raw operational evidence remain
outside the repository.
