# Day 14 Security Assessment and Remediation

Assessment date: 2026-10-07 (Asia/Taipei).
Status: draft for review; publication and Milestone 1 / Level 3 have not passed.
Canonical tracking: https://github.com/Jiang-Zheng-Xun/java-task-manager-portfolio/issues/28

## Scope and evidence sources

The initial scope covered baseline verification, official advisory applicability, inventory and coverage assessment, remediation proposals, and documentation. The user subsequently authorized application batch A, Site batch B1, and Boot plugin Jackson batch B2. The user remained the local single writer.

Local execution results and artifact hashes were supplied by the user. Attached inventories, manifests, and scanner JSON reports were read separately. Remote repository/material checks are distinct from local execution evidence. Historical Day 13 findings remain dated records, not current clearance.

No exploit, deployment, public visibility change, permission change, image update, or major framework migration was authorized or executed. Trivy and the separate Go/govulncheck analysis tools were explicitly authorized and created outside the repository; they did not replace the project's Java/Maven toolchain. Day 13 frozen documents are not modified.

## Baseline and regression

The local feature baseline was develop commit 44dba4b25628faa591c03e3fb23c04aecc1c222c. The user reported a clean tree, synchronized develop, and no other writer.

The baseline and each authorized batch regression reported 123 tests, zero failures/errors/skipped, and BUILD SUCCESS. These repeated runs do not represent an accumulated distinct test count. Passing tests support covered behavior, not complete security assurance.

PostgreSQL was reported healthy, bound to 127.0.0.1:5432, and exclusively used for replaceable project development/test data. No application was separately started during these batches. Test application contexts are separate evidence.

## Inventory and scanning

Trivy 0.75.0 was downloaded from the official release and SHA-256 verified. Tools, cache, inputs, and reports were stored outside the repository. The scanner did not scan .env or upload source code. The existing PostgreSQL image was scanned using the local Docker source only.

The initial application fs scan identified zero Java files despite exit 0. It is retained as unsuccessful coverage evidence, not zero-vulnerability proof. Corrected rootfs scans identified Java/JAR targets.

| Scan | Input / package records | Findings | Scanner severity |
| --- | --- | --- | --- |
| Initial application rootfs | 61 nested JAR paths / 62 records | 17 | Critical 4, High 6, Medium 7 |
| Initial project JARs | 94 inputs / 94 records | 17 | Critical 4, High 6, Medium 7 |
| Initial build JARs | 164 inputs / 174 records | 56 | Critical 5, High 27, Medium 24 |
| After A application | 62 records | 1 | Critical 1 |
| After B1 build | 162 inputs / 169 records | 29 | Critical 0, High 15, Medium 14 |
| After B2 build | 162 inputs / 169 records | 18 | Critical 0, High 10, Medium 8 |
| Final B2 application | 62 records | 1 | Critical 1 |

Findings overlap across groups and versions; counts are not added together. All 162 B1 and B2 manifest input paths have corresponding report paths. This does not independently verify local hashes or guarantee correct identity. Hibernate and selected build coordinate/version identification differences remain.

## Authorized changes and validation

Batch A adds application version properties: Jackson BOM 2.21.7, Tomcat 10.1.60, pgJDBC 42.7.12, and Log4j2 2.25.5. Boot 3.5.16 and Java 21 remain. Jackson annotations resolves to 2.21. Resolved inventory, full regression/package, and application scanning were obtained. The original 16 other application findings no longer appeared in that scan.

Batch B1 manages maven-site-plugin 3.22.0 through  pluginManagement. It does not add a Site server/deploy execution. Full clean verify/package passed. Comparing package-name/version/advisory tuples, 34 former build findings disappeared and 7 appeared, giving a net reduction of 27. New report entries included BeanUtils, Jetty, and jsoup; B1 is not all-clear.

Batch B2 explicitly adds Jackson core, databind, and parameter-names dependencies to spring-boot-maven-plugin, using the existing 2.21.7 property. The resolve-plugins report still listed 2.21.4. Observed Boot plugin class realm output instead showed all three at 2.21.7, with annotations 2.21. Preserve both sources and their discrepancy.

The B2 scan input retains the B1 inventory and substitutes these three observed realm versions. It is a derived input, not complete dynamic-realm proof. The 11 former Jackson build findings no longer appeared; no new tuples appeared. Full clean verify/package passed. This does not validate every Boot plugin goal, including unexecuted build-image paths.

Final application package-name/version records are unchanged from B1. User-reported final JAR SHA-256:
6d4e2712328072e2353d71e8ea81c92620089b8cbb37fe323c68ccb554099c0c

The final application and B2 build JSON identify Java/JAR targets.
Their original terminal warning/error lines were not supplied for review. Reported exit 0 does not establish security clearance.

## Remaining applicability and remediation decisions

The remaining application finding is spring-webmvc 6.2.19, CVE-2026-47884. Scanner Critical differs from the official Medium rating. The official advisory requires XsltView, a "/**" mapping leading to view rendering, and an implicit view name. Selected source/config/resource searches found no such usage. Other external or user-level configuration remains uncertain. This supports a limited inspected-path conclusion, not global non-exploitability. The publisher lists 6.2.20 as Enterprise only and 7.0.9 as OSS. No major migration or enterprise dependency adoption was approved.

The inspected Data JPA path uses fixed id ASC, a derived non-native query, and no caller-controlled Sort. It does not exhibit all inspected advisory prerequisites. This is not comprehensive SQL injection clearance. The OSS/Enterprise and major-version decision remains open.

The remaining 18 build findings cover Commons (5), HTTP components (3), Plexus Utils (3), Jetty (4), jsoup (2), and Snappy (1). Plugin attribution is not proof the affected methods executed. Site server/deploy was not executed; its parsing/rendering paths still need method/input applicability assessment.
The Jetty official table includes version-matching advisories absent from the scanner and support/availability conditions for older branch fixes. The jsoup Cleaner issue requires certain custom Safelist/raw-text behavior; actual Site usage has not been verified.

The PostgreSQL image is recorded as postgres:17.11-alpine, Alpine 3.24.2. Its reported local image ID and RepoDigest are: sha256:f02121de6f74d30d8a94cd1d9584125e2178d7e6c377d8130112d4e52d867995
The image scan lists 45 Alpine packages without findings, but gosu 1.19 / Go stdlib 1.24.6 has 46 findings: Critical 1, High 21, Medium 21, Low 2, Unknown 1. OS results do not clear gosu. Function/reachability assessment remains incomplete.

Ubuntu JDK package inventory is 21.0.12.1+1-1~22.04.4. Ubuntu backport/advisory verification, complete tool/dynamic provider coverage, and current CI environment inventory remain incomplete. Local Maven 3.6.3 is distinct from Maven libraries embedded in plugins. Selected official page access/redirect gaps remain explicit. Actions tags, image tags, and runner/local tools are not full SHA/digest pinning.

## History and publication material review

The user supplied 27 reachable commits and 135 blobs. Selected private-key/token/AWS-key/credential-URL and sensitive-path scans reported zero candidates. Unknown formats and unreachable objects were excluded. Zero candidates do not prove absence of secrets or private data.

The current remote review covered 28 Issue/PR entries including 13 PRs, and 26 runs/jobs/full logs. Selected patterns/line classification found no candidates. All 26 current artifact lists were empty. This is distinct from Day 13's historical 24-log review. It is not exhaustive manual review; expired/deleted materials are not covered.

Readable branch summaries reported protected=false. Detailed protection/ruleset access restrictions remain limitations. CI success is not branch protection.

## Decision and delivery boundary

A, B1, and B2 provide tested improvements, not complete SCA or security clearance. Unresolved applicability, environment, support, and coverage decisions remain. Recording these gaps is not risk acceptance or milestone approval.

Public repository publication is distinct from deploying a running service. The project's publication gate still needs an explicit evidence-based decision, final source/document review, delivery verification, and authorization. Main/default-branch presentation or promotion also requires a concrete decision. No checkbox completion automatically authorizes publication.

API contracts, production source code, schema, CI, permissions, and Day 13 frozen documents were not changed by these remediation batches. No new tests were added.

## Reflection supplement

Inventory, scanner findings, and official applicability answer different questions. Exit 0 without target recognition cannot establish coverage. Application dependency management does not automatically update plugin realms. The resolve-plugins/observed-realm discrepancy required actual execution evidence. Input path matches are useful but do not guarantee component identity accuracy.

Provide complete tool/input/report paths and stop points before execution. Use WSL explorer.exe with wslpath to open report directories. Preserve prior evidence and update the canonical Issue promptly. Guided Reflection review is not independent ability verification or Stable mastery. Question Bank writing/mastery changes remain unauthorized.

Future student-supervision/AI-agent product directions and resume updates are requested discussion topics, not implemented features or substitutes for this gate.

## Official references

- https://spring.io/security/cve-2026-47884/
- https://spring.io/security/cve-2026-47834/
- https://tomcat.apache.org/security-10.html
- https://jdbc.postgresql.org/security/
- https://logback.qos.ch/news.html
- https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21.7
- https://logging.apache.org/security.html
- https://jetty.org/security.html
- https://jsoup.org/news/release-1.23.1
- https://commons.apache.org/proper/commons-io/security.html
- https://maven.apache.org/guides/mini/guide-configuring-plugins.html

References support the dated inspected findings; they do not certify complete advisory coverage or permanent applicability conclusions.

## Gosu binary applicability evidence — 2026-10-07

User-local read-only inspection identified gosu 1.19, built with Go 1.24.6
for linux/amd64. Its SHA-256 was:
52c8749d0142edd234e9d6bd5237dff2d81e71f43537e2f4f66f75dd4b243dd0

The file was owned by root (uid/gid 0:0), mode 755, without setuid/setgid bits.
The inspected PostgreSQL entrypoint line used:
exec gosu postgres "$BASH_SOURCE" "$@"
This is static startup-script evidence, not a complete runtime call trace.

With separate authorization, Go 1.27.1 was downloaded and its official archive SHA-256 verified. govulncheck v1.8.0 was built outside the repository. The copied gosu binary matched the inspected SHA-256. Its build metadata identified github.com/tianon/gosu v1.19.0, github.com/moby/sys/user v0.1.0, and golang.org/x/sys v0.1.0.

The attached streaming JSON was parsed in full. It recorded binary mode, symbol scan level, and database last-modified time 2026-10-01T20:24:15Z. There were 45 module-level and 3 package-level finding events, with no function-level findings. The package-level events concerned GO-2026-4602, GO-2026-4864, and GO-2026-4970.
These 48 events are not 48 independent vulnerabilities.
All 46 Trivy gosu CVE identifiers had corresponding returned advisory records; presence in advisory records alone does not establish an applicable finding.

The terminal reported exit 0. The user subsequently supplied a zero-byte stderr result. JSON-mode exit 0 does not establish zero vulnerabilities. No affected function was reported by this analysis; this is bounded evidence, not proof that every vulnerable function is unreachable or that the image has no security risk. Binary analysis does not provide a complete call graph. The original Trivy findings remain preserved with this applicability supplement.

Tools, caches, copied input, and reports remained outside the repository. The user confirmed no additional POM/CI/image changes, no execution of the copied gosu binary, and no application startup.

Official methodology:
- https://github.com/tianon/gosu/blob/1.19/SECURITY.md
- https://pkg.go.dev/golang.org/x/vuln/cmd/govulncheck

This supplement does not approve publication, deployment, permissions, Milestone 1, or Level 3.

## Reviewed follow-up disposition — 2026-10-07

The user reviewed and approved recording this disposition and prototype positioning. This is not acceptance of all residual risks or publication approval. Function descriptions below include scanner evidence; incomplete official or actual-method applicability checks remain incomplete.

| Item | Evidence boundary and follow-up |
| --- | --- |
| Spring CVE-2026-47884 | Inspected paths did not show the required XsltView configuration. External configuration uncertainty remains. No major migration is approved. |
| Data JPA advisory | Inspected repository uses a derived non-native query, fixed id ASC, and no caller-controlled Sort. This is not global SQL injection clearance. |
| BeanUtils CVE-2025-48734 | Site dependency. Verify affected property-access methods and input/configuration before deciding applicability. |
| Commons IO CVE-2024-47554 | Compiler/Resources dependency. Verify XmlStreamReader usage and untrusted input; preserve official/scanner severity differences. |
| Commons Lang CVE-2025-48924 | Three reported versions across plugin dependencies. Verify affected methods and inputs in each relevant realm. |
| HTTP components CVE-2026-64607, CVE-2026-54399, CVE-2026-54428 | Boot plugin dependencies. Repackage was tested; build-image and affected HTTP paths were not safety-validated. |
| Plexus CVE-2025-67030 | Three reported versions across plugins. Verify extraction methods and archive sources; do not apply a blanket major-version override. |
| Jetty CVE-2026-2332, CVE-2024-6763, CVE-2026-10050, CVE-2026-6790 | Site dependencies. Site server was not started. Parsing/server applicability, additional official advisories, and branch support remain to assess. |
| jsoup CVE-2026-75140, CVE-2026-71497 | Site dependency. Verify actual parser, Safelist, and input. Lack of Site execution does not exclude every path. |
| Snappy CVE-2024-36124 | Jar plugin dependency. Verify affected decompression methods and whether untrusted compressed input is used. |
| Gosu/image | Preserve 46 Trivy findings and the binary analysis supplement with zero function-level findings. No blanket exclusion or image rebuild. |
| JDK/Maven/CI | Ubuntu backport/advisory, CI environment, and selected official-source coverage gaps remain. Tool-environment clearance is not established. |
| Pinning/permissions | Existing tags are not complete immutable pinning. Restricted permission reads remain limitations. Changes require separate proposals. |

These items define follow-up verification work, not additional execution authority. No follow-up completion date or remediation version is inferred.

Reviewed portfolio positioning:
A verifiable Java/Spring Boot backend prototype with API, PostgreSQL persistence, 123-test regression and CI evidence. Security review and remediation evidence have dated scope limitations. Comprehensive security validation is incomplete.
No publicly running service or production-readiness claim is included.

Before publication, complete final staged content review and delivery verification, review residual risks explicitly, decide the default-branch presentation, and obtain explicit visibility-change authorization.
