# Task Evidence verification

This page summarizes how the Task Evidence feature was verified, batch by batch, and what the evidence does not show. Batch codes (r12–r22) refer to separately recorded verification runs. Per-class details are in [task-evidence-regression-index.tsv](task-evidence-regression-index.tsv).

## Regression set (42 classes)

| Batch | Classes | Testcases | Passed | Skipped |
|---|---|---|---|---|
| r15 (original run) | 7 | 90 | 90 | 0 |
| r15-continue-resume | 1 | 20 | 20 | 0 |
| r16 | 12 | 69 | 69 | 0 |
| r17 | 1 | 29 | 29 | 0 |
| r18 | 10 | 121 | 121 | 0 |
| r19 | 4 | 220 | 220 | 0 |
| r20 | 1 | 52 | 47 | 5 |
| r21 | 5 | 35 | 0 | 35 |
| r22 | 1 | 13 | 13 | 0 |
| **Total** | **42** | **649** | **609** | **40** |

- Failures and errors are 0 in every class.
- Each class uses exactly one accepted result; nothing is added twice.
- The results were obtained in separate batches, not as one complete suite run.
- The 40 skips are CONTROL tests that are disabled unless a control property is set. They show only that the disabled condition works, not that the control functions pass.
- From r16 onward, most classes ran with `surefire:test` on existing compiled classes. That does not re-prove that the classes match the sources.
- r22 ran against a real local test database. Guard and identity success is inferred from the context starting and the tests running; there is no direct event log.

The whole `TaskEvidenceCommitIntegrationTest` is not part of the 42-class set. It is reported separately below.

## Commit integration test (separate)

### Nine non-concurrent methods

- **r12:** 9 tests, 1 failure, 0 errors, 0 skipped. Eight passed. One failed because the test assumed a converter index position.
- **r13:** STOP at compilation. A test variable named `body` clashed with another name.
- **r13-resume:** the test was corrected, with no product change. The previously failing method then ran: 1 test, 0 failures, 0 errors, 0 skipped.

The nine methods therefore passed across batches. r12 is not restated as 9/0/0/0.

### Seven concurrent methods

- **r14:** each method had its own Maven run, and each returned 1/0/0/0. That is seven limited results.
- PID blocking, gate timing, Future results and worker exit support the specified scenarios only. They do not cover every cancellation, cleanup fault or pool reset.

Different batches, and reruns of the same method, are not added together as extra coverage. These results are not included in the 649 total.

This section cites the earlier accepted conclusions. It is not a new execution, and the historical XMLs were not re-reviewed for this document.

## Historical deviations kept on record

- **r14 packaging:** `row*` text files were wrongly included in a set, an archive was packaged even though a grep had failed, and step-by-step rc records are missing. These deviations belong to r14 packaging only, not to every batch from r12 to r14.
- **MetadataFactory guard test:** before running the test in r18, the direct path was not read sufficiently. r19 added a post-hoc clarification, but its statement about type queries was too broad; it was later narrowed to the fixed fixture in that test. The `false` arguments to `getType` / `getBeanNamesForType` do not guarantee that an existing FactoryBean singleton's `getObjectType()` is never called.
- **r15:**
  - The run was interrupted, and when the fault started is unknown.
  - NUL bytes and zero-byte XML reports were found.
  - 8 classes (99 rows) were withdrawn. The original 7 classes (90 rows) remain valid.
- **Tooling and procedure faults:** PATH and `comm` sorting errors; one empty first invocation; r18–r20 ended as STOP, with evidence saved after the failure; one closeout shell failure; one completion-script failure.
- **Mockito self-attach:** in r16 it happened without a recorded authorization basis, in seven classes (ListTaskEvidenceServiceTest and the six Task service tests). The other five r16 classes are not affected. Later batches recorded a batch-specific authorization.
- **r23:** STOP. The post-run hash step derived its source list from the post-run untracked set, which wrongly included the new documents. r23's report also described checks as completed when the stop record listed them as missing.

## Evidence limits

- No claim of zero side effects, unchanged sequences, or an empty database after the DB tests.
- Secret scanning covered raw, base64 and URL-encoded forms. It cannot exclude every encoding.
- Process checks covered the Maven process group and its children. A process that left the group and was reparented cannot be detected.
