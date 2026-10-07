# Day 13 — Milestone acceptance evidence and security review

Date: 2026-10-06 (Asia/Taipei).
Canonical tracking: [Issue #26](https://github.com/Jiang-Zheng-Xun/java-task-manager-portfolio/issues/26).

## Goal and scope

Map Milestone 1 criteria to existing evidence and review the Level 3 safety gate. Document findings without changing production code, dependencies, API contracts, schema, CI configuration or permissions.

Milestone 1 and Level 3 have not passed. Publication and deployment are not authorized. Dependency remediation requires a separate proposal and approval.

## Work and evidence

- Verified the local baseline at   `d38a07fe41db9a091b897987d0f1995178259b9f`, synchronized with the cached origin/develop reference, with a clean working tree.
- Created `feature/day-13-milestone-security`.
- Verified Java 21.0.12.1, Maven 3.6.3 and healthy local PostgreSQL `17.11-alpine` bound to `127.0.0.1:5432`.
- Verified ignored/untracked `.env` and loaded DB password without disclosure. The user confirmed an exclusive project development/test database with no production, customer or irreplaceable data and no concurrent writer.
- Passed the baseline full regression: 123 tests, zero failures, errors or skipped tests, BUILD SUCCESS. Reading the 19-class Surefire inventory did not rerun tests. No tests were added.
- Completed the acceptance evidence matrix and created
  `docs/milestone-1-acceptance.md`, separating current local output, remote reads and historical Day 12 live observations.
- Reviewed reachable Git history: 25 commits and 133 unique blobs, with no candidates under the selected sensitive-path/content rules.
- Retrieved readable Issue/PR material and all 24 enumerated Actions job logs. Selected secret patterns found no candidates; inspected password lines used the explicit CI-only value. Current artifact lists reported zero.
- Read main/develop branch state as protected=false. Detailed protection and ruleset reads were restricted. CI success is separate from protection.
- Obtained the runtime dependency tree successfully and recorded conditional official-advisory findings for Tomcat, Spring Data JPA, pgJDBC and Logback. No dependencies were upgraded and no exploit was executed.

These scans are limited evidence, not proof that all secrets or vulnerabilities are absent. Full SCA, test/build dependencies, JDK and container OS packages remain outside the completed coverage. Deleted/expired artifacts and unreachable Git objects are not covered.

## Technical understanding

A successful dependency-tree command establishes inventory, not security. An affected version must be assessed against the advisory's feature, configuration, input-source, attacker-privilege and reachability conditions. An unused feature may limit exposure only when supported by evidence.

The project's Level 3 is the milestone/publication safety gate, not a process maturity certification. Passing functional tests cannot replace review of secrets, history, logs, dependencies, permissions and environment isolation.

Runtime call order differs from source dependency direction. The application defines the repository port and uses Spring annotations. MockMvc with PostgreSQL is not a live network-server test; localhost curl does not establish deployment readiness.

Flush/clear is not commit. JDBC observation outside a transaction need not use another physical pooled connection. New cross-precision HTTP/DB timestamp comparisons allow at most one microsecond; unchanged persisted values and replay timestamps use exact comparison.

Collection integration tests include rollback-managed transaction-local table deletion. Non-transactional committed PATCH/DELETE tests clean their owned IDs. The Day 11 PATCH evidence gap is closed.

## Reflection

### Outcomes and evidence

The baseline regression, test inventory, acceptance matrix and scoped security review support a documented assessment. Open findings prevent treating that assessment as milestone or Level 3 acceptance.

### Problems and corrections

The assistant unnecessarily required Bash for simple Git checks; subsequent steps use the user's zsh shell. Security work was advanced before the overall D13-03 mapping gate was closed; the complete matrix was subsequently written to the canonical Issue and reread.

Initial Reflection explanations overstated non-exploitability and confused Level 3 with process maturity. These were corrected during review. The follow-up confirmation represents understanding after guidance, not an independent mastery assessment.

### Process improvements

Close every condition of a compound gate before checking it. Keep local, remote and historical evidence distinct. Read complete new-file diffs: ordinary git diff --check does not inspect untracked files; staged review must cover their full contents and whitespace.

Review Reflection before freezing the full Daily Log and opening the primary PR. Later regression, CI, merge and cleanup metadata belongs in the canonical Issue rather than being added retroactively to the frozen log.

### Question Bank candidates

Dependency inventory versus vulnerability assessment; advisory applicability; functional-test evidence versus a security gate. These are candidates only. No Question Bank write or mastery/status change is authorized.

## Remaining boundaries

- Dependency remediation and compatibility assessment require a separately approved proposal.
- Milestone 1 and Level 3 remain not passed.
- Publication, deployment and permission changes remain unauthorized.
- Shared Question Bank Stage 2 aggregate validation remains incomplete.
- The historical Day 6 72/70 test-count discrepancy remains unresolved and is not a test failure; it does not affect the verified baseline of 123 tests.
- Product Direction Gate is a subsequent decision, not automatic scope.

## Time record

Approved start: 09:46:46 CST; Normal maximum 07:00:00 effective time; latest completion guardrail 20:30 CST.

At the reviewed Reflection confirmation, 11:25:01 CST, no rest or pure external waiting had been reported. Effective time was 01:38:15. This is a checkpoint, not final Day 13 working time. Final reconciliation follows Issue closure.
