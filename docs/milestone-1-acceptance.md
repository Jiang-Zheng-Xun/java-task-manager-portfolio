# Milestone 1 acceptance and security review

Review date: 2026-10-06 (Asia/Taipei).
Canonical tracking: [Day 13 Issue #26](https://github.com/Jiang-Zheng-Xun/java-task-manager-portfolio/issues/26).

## Outcome and scope

The implementation evidence mapping and the scoped security review have been completed. Milestone 1 acceptance and the Level 3 gate have NOT passed.

Known dependency findings, review coverage gaps and access restrictions remain recorded below. The user approved documenting these findings today without upgrading dependencies. Substantive remediation requires a separate proposal and approval. Publication, deployment and permission changes are not authorized.

This document assesses the existing implementation at baseline commit `d38a07fe41db9a091b897987d0f1995178259b9f`. It does not introduce features, change API contracts or provide a deployment
readiness guarantee.

## Evidence sources

- Current local evidence: user-supplied Day 13 command output and Surefire XML.
- Remote evidence: repository files, Issues, PRs, Actions logs and readable branch state inspected during the review.
- Historical evidence: Day 12 documentation, accepted daily record and live localhost demo. These observations are not fresh Day 13 execution.

The Day 13 baseline full regression passed with 123 tests, zero failures, errors or skipped tests. Reading the XML inventory did not rerun the tests. Repeated runs do not increase the number of distinct tests.

## Acceptance evidence matrix

| Criterion | Evidence | Boundary |
| --- | --- | --- |
| Application baseline | Java 21, Spring Boot 3.5.16; current application context test passes | No fresh Day 13 application startup or deployment |
| POST and GET | Controller and full API tests; generated ID and Location; historical live POST/GET | MockMvc is not a live network-server test |
| Collection and pagination | Empty results, ascending IDs, default and bounded sizes, next-page headers and out-of-range page tests | Offset pagination is not a concurrent snapshot; Day 12 live list covered one page |
| PATCH status | Completion/reopening lifecycle and committed JDBC observation; replay preserves persisted timestamp | Transaction-local lifecycle and committed evidence are distinct |
| PUT replacement | Explicit description including null, immutable fields, replay and rejected-request DB assertions | Full API PUT evidence does not establish an independent committed observation |
| DELETE | Automated empty 204 response, subsequent 404 and committed JDBC observation | Historical initial live DELETE 204 was user-reported without that response's headers |
| Persistence and migration | Flyway V1, Hibernate schema validation, repository and direct DB constraint tests | No backup/restore or production-data claim |
| Validation and HTTP errors | Representative 400/404/405/415/406/500 contracts and safe response tests | Not exhaustive security testing; 406 negotiation is not guaranteed before business logic |
| Architecture | Application-defined repository port, persistence adapter and constructor injection | Runtime call order differs from source dependency direction; application uses Spring annotations |
| Documentation | README, architecture, demo, ADR 0001 and Day 12 log | Historical ADR branch-protection rationale is not proof of enabled protection |
| CI | Day 12 PR run 37285591553 and post-merge run 37286223644 succeeded | No Day 13 delivery CI yet; CI success does not verify local state or branch protection |

Current baseline test inventory:

| Layer | Tests |
| --- | ---: |
| Application context | 1 |
| WebMvc controllers | 50 |
| Application services | 17 |
| Domain | 13 |
| Persistence mapper | 2 |
| Repository integration | 10 |
| Direct database constraints | 9 |
| Full API integration | 21 |
| Total | 123 |

The 19-class inventory reports zero failures, errors and skipped tests. Collection integration tests include transaction-local table deletion under an outer rollback-managed test transaction. Non-transactional committed PATCH and DELETE tests clean their owned IDs. Do not describe all tests as deleting only owned IDs.

Flush/clear is not commit. JDBC observation outside the application transaction does not require a different physical pooled connection. New HTTP/DB timestamp comparisons allow at most one microsecond across precision boundaries; unchanged persisted values and replay timestamps use exact comparisons.
Day 11 filled the historical PATCH evidence gap.

## Security review coverage

| Area | Observations | Limitations |
| --- | --- | --- |
| Local reachable Git history | 25 commits and 133 unique blobs; zero selected sensitive-path/content candidates | Excludes unreachable objects, unknown secret formats and other historical credentials |
| Issues and PRs | 26 Issue/PR entries, 12 PR entries and readable comments/reviews retrieved; selected patterns found no candidates | Pattern scanning and selected security-text review are not exhaustive manual line-by-line review |
| Actions logs | All 24 enumerated workflow job logs retrieved and inspected with selected secret patterns; inspected password lines use the explicit CI-only value | Does not establish absence of arbitrary historical secrets |
| Attachments and artifacts | No attachment URL candidates extracted from inspected bodies; all 24 current artifact lists report zero | Deleted or expired material is not covered |
| Branch state | main and develop report protected=false | Detailed protection read returned integration-access 403; rulesets read returned a plan restriction |
| Reproducibility | Versioned PostgreSQL image tag, Java 21 and Boot-managed dependencies | Actions use @v4, image is not digest-pinned, ubuntu-latest and local tools are not fully pinned |
| Local isolation | Localhost DB/server settings, ignored and untracked .env; user confirms exclusive project development/test DB and no concurrent writer | Configuration and user reports are not a new data-content read |

The local PostgreSQL binding was `127.0.0.1:5432`; the retained volume was `java-task-manager-portfolio_postgres-data`. Volume existence does not prove its contents. Historical Task 32 content preservation evidence comes from the Day 12 demo GET.

No exploit, dependency upgrade, Git-history rewrite or permission change was performed during this review.

## Runtime dependency findings

The user-supplied runtime dependency tree completed successfully and left the working tree clean. Runtime scope includes compile/runtime dependencies, but excludes test-only dependencies and build plugins.

This is a focused official-advisory review, not exhaustive software composition analysis (SCA).

| Component | Resolved version | Finding and applicability |
| --- | --- | --- |
| Embedded Tomcat | 10.1.55 | Falls within multiple announced affected ranges, including CVE-2026-86350 affecting HTTP/2 header handling, fixed in 10.1.60. Version presence alone does not prove exploitability; protocol and configuration conditions need further assessment. |
| Spring Data JPA | 3.5.13 | Matches CVE-2026-47834. The inspected repository uses a derived query with fixed ID ascending order and PageRequest.of(page,size), without native SQL or caller-controlled Sort. The advisory's combined prerequisites are absent in that path; this is not general SQL-injection clearance. The listed 3.5.14 fix is enterprise-only. |
| pgJDBC | 42.7.11 | Matches CVE-2026-54291, fixed in 42.7.12. The advisory requires channelBinding=require and TLS interception conditions. Inspected DB_URL has no query parameters; all additional driver/TLS settings were not independently verified. |
| Logback | 1.5.34 | Predates the definitive CVE-2026-13006 fix in 1.5.37. The published fix removes Janino conditional-expression processing; the supplied runtime tree contains no Janino. This limits the inspected classpath exposure without clearing all logging risks. |

Official sources consulted on 2026-10-06:

- [Apache Tomcat security advisories](https://tomcat.apache.org/security-10.html)
- [Spring Data JPA CVE-2026-47834](https://spring.io/security/cve-2026-47834/)
- [pgJDBC security advisories](https://jdbc.postgresql.org/security/)
- [Logback release news](https://logback.qos.ch/news.html)
- [Logback conditional configuration](https://logback.qos.ch/manual/configuration-conditional.html)

These findings are not Maven test failures or evidence of an observed compromise.

## Open items and acceptance boundary

- Assess dependency remediation and compatibility in a separately approved proposal; do not automatically migrate major framework versions.
- Complete any additional dependency/SCA coverage required for the gate, including test/build dependencies, JDK and PostgreSQL image/OS packages.
- Retain unreadable protection/ruleset evidence as an access limitation.
- Perform separate publication/deployment review and obtain authorization before changing repository visibility, deployment or permissions.
- Shared Question Bank Stage 2 aggregate validation remains incomplete.
- The historical Day 6 72/70 test-count discrepancy remains unresolved. It is not classified as a test failure and does not affect the verified Day 12 or Day 13 baseline count of 123.
- Milestone 1 and Level 3 remain not passed. Product Direction Gate is a subsequent decision and is not automatically authorized by this review.

Later delivery regression, PR/CI, merge and cleanup evidence belongs in the canonical Issue. It must not retroactively alter frozen historical daily logs.
