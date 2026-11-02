# Task Evidence API — 2026-11-02

## Stable results

- The Evidence API was implemented, with POST/GET `/api/tasks/{id}/evidence` and migration V2. Its contract is in `docs/task-evidence-api.md`.
- The 42-class regression set was verified across batches: 649 testcases, 609 passed and 40 skipped (CONTROL disabled), 0 failures and 0 errors. This was not a single complete suite run.
- The Commit IT was verified separately: nine non-concurrent methods passed across r12 and r13-resume, and seven concurrent methods ran one Maven run each in r14. These are not added to the 649 total.

## Reflection

### What was verified, and at what level
- Unit, MVC-slice and guard tests ran without a database. The adapter DB test ran against a real local test database, with guard and identity success inferred from startup. Most later batches used existing compiled classes.

### Technical learning
- **Unicode:** NUL and unpaired-surrogate rejection, boundary trimming with `isWhitespace`/`isSpaceChar`, and limits counted in code points. The DB CHECK is deliberately weaker than the Java rules.
- **JSON converter:** a converter scoped to one DTO, built on `mapper.copy()` and its own `ObjectReader`, can tighten parsing without changing the shared mapper.
- **Locking and concurrency:** a transaction-local `lock_timeout`, `FOR KEY SHARE` on the parent row, and insert/flush before commit. Concurrent tests show only the specified scenarios.
- **Pagination:** check that the Task exists before handling large offsets. Report `X-Has-Next-Page` instead of a total. There is no cross-page snapshot.
- **Guard reuse:** the existing test-database guard, identity verifier and Flyway strategy were reused for the Evidence DB test.

### Failures and how they were handled
- A test assumed a converter index position (r12), and a variable-name clash broke compilation (r13). Both were fixed in the test, without product changes.
- The r15 run was interrupted (fault start unknown), and NUL/zero-byte XML appeared. 8 classes (99 rows) were withdrawn and the original 7 classes (90 rows) kept.
- Shell and tool defects, by batch:
  - **Earlier batches:** PATH and `comm` errors.
  - **r18:** STOP; a zsh `noclobber` overwrite was refused.
  - **r19:** STOP; the completion-write script failed after packaging, and a deletion was refused.
  - **r20:** STOP; the closeout command failed to parse in the outer shell.
  - Records and timing for some of these events are incomplete or still unknown.
- **r23:** STOP. The post-run hash step took its source list from the post-run untracked set, which wrongly included the new documents. r23's report also described checks as completed when the stop record listed them as missing. Whether r24 resolves this depends only on r24's own recorded checks.

### Collaboration improvements
- One formal brief per batch, reusing earlier reading, checking every checkbox in full, and never rerunning or repackaging accepted evidence.

### Question Bank candidates (not added to the bank)
- Why must Java and the database disagree on blank-content rules here?
- What does `FOR KEY SHARE` allow and block compared with `FOR UPDATE`?
- What does a CONTROL test's skip prove, and what does it not prove?

This Reflection does not claim that AI work was faster or cheaper, and it is not evidence of the user's independent ability.

## Limits

- Effective working time for 11/1 is not determined and is not estimated across the fault interval.
- 11/2 started at 10:39:36 CST. Its working time is not yet settled and is not derived from Claude batch times.
