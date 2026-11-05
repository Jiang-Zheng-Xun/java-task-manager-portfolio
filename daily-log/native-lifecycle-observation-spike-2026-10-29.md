# Native lifecycle observation spike — 2026-10-29 to 2026-10-30

Work log for Refs #37. The work ran across midnight in Asia/Taipei (UTC+8): from 2026-10-29 through the early hours of 2026-10-30. It was done as a series of short, separately approved, time-boxed batches.

## 1. Scope and delivery status

- This is a bounded, test-only, non-DB spike. It observes native lifecycle events of `org.postgresql.Driver.connect` and the HikariCP `HikariDataSource` no-arg constructor, `close` and `getConnection`, using a startup Byte Buddy agent.
- The approved controls use an unsupported JDBC URL, a null URL, or the explicitly scoped Hikari lifecycle calls. Their accepted results are limited to those inputs and assertions; this is not a general guarantee that every possible execution avoids a database connection. Spring, Flyway and containers are not started, and the production code and `pom.xml` are not modified.
- Delivery status: the four source files are untracked and uncommitted on branch `feature/native-lifecycle-observation-spike-20261028`, based on `1dd1a2039a12c58ba6deb4aecf6c0a00566c8b36`.
- None of the Git delivery loop has run yet: no commit, no push, no pull request, no CI and no merge.
- Issue #37 stays open.

## 2. Accepted contracts

These contracts have been accepted after independent review (ChatGPT) and owner acceptance. Each one is deliberately limited.

- **G3 item 4 — transform failure (T1).** "Stop" means the observation is reported INVALID and its result is refused. It does not guarantee that native class loading or the native call is aborted in the JVM.
  - T1 was validated once, on an earlier source version: the induced transform failure reached `onError` as the original `InducedObservationFailure`. The Driver still loaded and instantiated, and the unsupported-URL `connect` returned null. The result was INVALID and `acceptedCount` was refused.
  - This one observed outcome is not generalised to every transform failure. Any other native outcome makes T1 fail and requires separate review.
- **G3 item 5 — exit-advice faults (H2–H5), limited fault model.** An induced one-shot fault in the exit advice is suppressed, makes the observation INVALID, and leaves the native behaviour unchanged:
  - H2: a null return;
  - H3: a native `SQLException` with no cause and no SQLState;
  - H4: a normal `close`;
  - H5: normal completion of the constructor.

  Not claimed: transparency or recovery for every Throwable, failures of the handler itself, linkage failures, OutOfMemoryError or StackOverflowError. Exceptional constructor completion and the pre-super path remain open gaps.
- **G3 item 6 — boundary and concurrency controls (S1, S2, S3a, S3b, S4, P1n).** The dynamic evidence has been accepted: each control ran once on the current source with rc=0 and 2/0/0/0. Overall acceptance of G3 still waits for owner review of this document and the source freeze.

## 3. Source identity

| Path | SHA256 |
|---|---|
| `src/test/java/io/github/jiangzhengxun/taskmanager/support/testdb/NativeLifecycleAdvice.java` | `01c7040f1031fb50f17790ef025f7dc6ec109369f4ef1c32b9bd1c46271705c7` |
| `src/test/java/io/github/jiangzhengxun/taskmanager/support/testdb/NativeLifecycleInstrumentation.java` | `f6a06f0b56df46233603f3a487284ba943a1c82bd89df4b9f08e6bc0ebc3e157` |
| `src/test/java/io/github/jiangzhengxun/taskmanager/support/testdb/NativeLifecycleObservation.java` | `a3050c239499574844668a33e1a8c5a0434c1492d545c7b54829f28949c53c8e` |
| `src/test/java/io/github/jiangzhengxun/taskmanager/support/testdb/NativeLifecycleObservationTest.java` | `dbb8681bc460c790387242fe993ac96f6191445a186aa53f5822b95a13d2f279` |

- `pom.xml` is unchanged (`f57c3f70c1a3fd12cfa83e52670280e331848eb8d9b5ff107b000509e8a3a310`). Branch and base HEAD are as given in section 1.
- No delivery commit exists yet. The freeze set for the planned commit is these four files plus this document.
- The test class has 24 `@Test` methods: one startup check, enabled for any control, plus 23 controls, each enabled by exactly one control ID.

## 4. Evidence matrix

Each selected control was executed once in its own fresh JVM. A table row may summarise several such runs. The counts are per run and must not be added together as if they came from one suite. The default-skip run is one separate run (24/0/0/24) and is not 24 passing observations. Evidence is held in private batch archives, identified below by batch name and archive SHA256. These archives are not public downloads.

| Control or batch | Source identity | Result | Evidence level | Limits |
|---|---|---|---|---|
| S1, S2, S3a, S3b, S4, P1n — `g3-six-concurrency-validation-20261029T233842-955b2cc7` (zip `1a0ecec5…70c14`) | Current source (table above) | Each rc=0, 2/0/0/0, with the exact two testcases. S1's first run recompiled 33 test sources; the next five did not recompile. | Same final source; independently reviewed and accepted | S1–S4 are synthetic-Session controls with no transformer, although the JVM still loads the startup agent. P1n is a controlled native boundary experiment. Each control ran once. |
| A, C, E, G, H, F, P5 — `g3-final-regression-20261030T001743-d733cea6` (zip `25f34223…993cd`) | Current source | Each rc=0, 2/0/0/0, with the exact two testcases. No recompilation. | Same final source; reviewed and accepted | Confirms these controls still pass with the new hooks and the freeze pause switched off by default. |
| default-skip — same batch | Current source | rc=0, 24/0/0/24, every skip reason exact, explicit empty `-DargLine=`, no control property, no agent | Same final source; reviewed and accepted | The 24 skips show that the controls are off by default. They are not 24 passing observations, and they do not re-run the six concurrency controls. |
| T1, H2–H5 — `t1-h2-h5-validation-recovery-20261029T210057-a2ad9ed7` (zip `1c1ca8f5…78c3`) | Earlier source (Instrumentation `9d817f4c…`, Observation `fa57974b…`, Test `71d2ebd4…`) | Each rc=0, 2/0/0/0 | Earlier source; accepted | Not re-run on the current source |
| A, C, E, G, H, F, P5, default-skip (18 methods) — `t1-h-regression-recovery-20261029T213409-38805b67` (zip `99aef167…2101`) | Earlier source | Each rc=0; default-skip 18/0/0/18 | Earlier source; superseded by the final regression for these controls | History only |
| B, D, N1–N3, missing-agent negative control | Earlier sources (historical acceptance input) | Accepted earlier | Earlier source | Not re-run on the current source |

Implementation batches (static changes only, no tests run): `g3-s1-s2-implementation-20261029T221514-f2d3fb15` (zip `4c8b4e9b…24fd`), and `g3-s3-p1n-recovery-20261029T230815-f9284561` (zip `f53f62cb…556c`). The plan revision is `g3-convergence-correction-20261029T112308-bf048e63` (zip `7f00a2c7…8887`).

Not exercised dynamically:
- failures, timeouts and interrupts of the hooks or of the freeze pause;
- a `finish()` that throws RuntimeException or Error;
- some partial-cleanup branches.

## 5. Lifecycle and concurrency boundaries

- **`finish()`:** the whole method is `synchronized`. The first call closes admission and freezes the events under the session write lock, then removes the transformer, then detaches the session. Concurrent and later calls get the same published result instance, and the cleanup sequence is claimed at most once (P5).
- **Session hooks (test-only):**
  - positions `BEFORE_ADMIT_LOCK`, `INSIDE_ADMIT_LOCK` and `AFTER_FAILURE_COUNT`;
  - empty by default; at most two installed at a time;
  - each hook is one-shot by CAS and waits at most 10 s for its release;
  - a timeout, interrupt or other failure is recorded on the hook and never counted as an observer failure.
- **Freeze pause (test-only):**
  - a per-instance `FreezePause` in `NativeLifecycleInstrumentation.finish()`, placed after the freeze and before transformer removal and detach;
  - off by default, one-shot, at most a 10 s wait;
  - releasing it or reading its diagnostics never needs the `finish()` monitor;
  - the pause records the failures caught by its handler, and the surrounding `finish()` code retains the reset and detach finally paths; this does not guarantee recovery from failures in the handler itself, Errors, or failures of the cleanup actions.
- **Synthetic versus native controls:** S1–S4 exercise an empty-required synthetic Session directly. P1n uses the real Driver.
- **P1n:** three pauses fix this order: ENTRY admitted inside the lock → freeze → EXIT, which has captured the original session, admitted only after the result is published and counted as a late event.
  - P1n deliberately breaks `finish()`'s caller obligation that all observed native calls have returned. That overlap is not a supported use.
  - A return from `finish()` does not prove that native worker threads have ended.
- **S4:** one run of 8 workers and 16 events produced a complete, unique sequence 1..16. This does not show that every race is excluded.

## 6. STOP history and process improvements

Several batches stopped at their first unexpected failure instead of being retried or repaired. Those STOPs are kept as they happened; none is rewritten as a success. They included:

- an evidence-scope STOP;
- a denied permission-gated Read;
- a syntax error in an evidence script;
- tool calls issued after a first failure;
- a JUnit-versus-AssertJ message-format mismatch;
- the startup method's `".*"` being miscounted as a control ID;
- a contradiction in an order about the empty `-DargLine=`;
- a STOP record whose parent directory did not exist, and intermediate results that were never saved;
- an original P1n ordering that the source could not guarantee, because the EXIT session lookup raced the detach;
- gaps in recording the outer tool calls.

Improvements adopted, judged only by what was observed in later batches:

- each evidence script is syntax-checked with `ast.parse` before it runs, and re-checked after any edit;
- each check is saved as soon as it is made, and the first mismatch stops the run;
- error directories are created before any read;
- historical argv arrays, including the empty `-DargLine=`, are compared element by element;
- each run's report freshness and process-group shutdown are verified;
- the archive's file list is fixed before packaging;
- reads go through the script inside pre-approved paths.

Later accepted batches used these checks and completed with the recorded outcomes. No comparative reduction in errors or efficiency gain has been measured, and recurrence is still possible.

## 7. Reflection

- **Roles:** Claude executed the approved batches. ChatGPT reviewed the evidence independently. The repository owner made the decisions and gave acceptance. This record therefore documents a supervised, AI-executed workflow, and it is not evidence of the owner's independent implementation skill.
- **Technical learning:**
  - A useful observation contract must state what "stop" does not guarantee.
  - Boundary ordering is only testable when every racing step can be held at an explicit pause point. The P1n gap appeared only after the source was read closely.
- **Still to improve:**
  - fewer STOP/recovery cycles caused by the evidence scripts themselves rather than by the system under test;
  - earlier static review of race windows before writing a control.
- No reliable data exists for effective working hours, speed-up, cost savings or an efficiency comparison, so none is stated.

## 8. Remaining delivery work

1. Owner review and acceptance of this document and of the source freeze (four sources plus this file).
2. A separately approved Git commit of exactly those five files, then a draft pull request against `develop` that uses `Refs #37`.
3. CI on the pull request.
4. The owner marks it ready and merges.
5. Post-merge verification and cleanup.
6. Closing the issue: Issue #37 is closed only after the merge, never in advance, and the pull request must not close it automatically.
