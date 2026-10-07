# Day 14 — Security Assessment and Targeted Remediation

Date: 2026-10-07 (Asia/Taipei)
Canonical Issue: https://github.com/Jiang-Zheng-Xun/java-task-manager-portfolio/issues/28

## Scope and baseline

The user authorized baseline verification, advisory applicability review, inventory and coverage assessment, remediation proposals, and documentation. Application batch A, Site batch B1, and Boot plugin batch B2 were subsequently authorized separately. The user remained the local single writer.

The local feature baseline was develop:
44dba4b25628faa591c03e3fb23c04aecc1c222c

User-local execution reports, attached report analysis, remote checks, and historical Day 13 evidence are distinct sources.

## Completed technical results

- Baseline and authorized batch regressions each reported 123 tests, zero failures/errors/skipped, and BUILD SUCCESS. Repeated runs are not added.
- Batch A overrides Jackson BOM 2.21.7, Tomcat 10.1.60, pgJDBC 42.7.12, and Log4j2 2.25.5.
- Batch B1 manages maven-site-plugin 3.22.0 without adding server/deploy execution.
- Batch B2 supplies three Jackson dependencies at 2.21.7 to the Boot plugin. Observed realm evidence differs from the resolve-plugins inventory; both sources and the derived scan-input limitation are preserved.
- Full clean verify/package succeeded for the tested modifications.
- Final application scan identified 62 package records and one Spring finding.
- Final build scan used 162 JAR inputs, identified 169 package records, and reported 18 findings.
- Initial application coverage failure and prior reports remain preserved. Exit 0 alone does not establish coverage or security clearance.
- The existing image scan reported 46 gosu findings, while 45 Alpine package records had no reported findings. These results are separate.
- Gosu binary analysis recorded 45 module-level and three package-level finding events, with zero function-level findings. This is bounded evidence, not proof of global non-exploitability.
- Selected reachable-history scans covered 27 commits and 135 blobs. Remote review covered 28 Issue/PR entries and 26 runs/jobs/full logs. Zero selected-pattern candidates and empty current artifact lists do not establish exhaustive secret or historical-material clearance.

Final user-reported application JAR SHA-256:
6d4e2712328072e2353d71e8ea81c92620089b8cbb37fe323c68ccb554099c0c

Detailed findings, report comparisons, official references, applicability limitations, and follow-up disposition:
[Day 14 security assessment](../docs/security-assessment-day14.md).

## Evidence and remaining limits

Spring/Data JPA conclusions are limited to inspected paths and prerequisites. External configuration uncertainty remains.
Application dependencies, plugin realms, image components, JDK/Maven, and CI environment evidence are assessed separately.
Official/scanner severity differences and component-identification differences remain explicit. Comprehensive SCA and security clearance are not established.

API contracts, production source code, schema, and CI were not changed. No new tests were added. Day 13 frozen documents remain unchanged. Private development services remain separate from public repository decisions. Publication, deployment, permissions, and Milestone 1/Level 3 acceptance require their own evidence and explicit decisions.

## Reflection

Inventory identifies components and versions; scanner findings record database matches; official applicability requires functionality, configuration, input, attacker permissions, and reachability evidence.

Application dependency management does not automatically change plugin realms. Actual Boot realm evidence was needed to verify the authorized Jackson changes.

The first application scan identified no Java target. A successful command exit could not prove that nested libraries were analyzed. Corrected scanning and input/report comparison supplied coverage evidence with remaining limitations.

Gosu version matches and binary analysis answer different questions. Zero function-level findings do not erase module/package matches or the original Trivy report. Repeated tests and overlapping findings are not summed.

Provide complete tool/input/report paths and stop points together.
Use WSL explorer.exe with wslpath for report access. Keep the canonical Issue current and avoid repetitive confirmation steps.
Forms assist review; prefilled values are not independent verification.

Longer available time does not relax evidence or authorization requirements. Future work should have concrete verification conditions and scope boundaries. This Reflection was reviewed with guidance; it is not independent ability verification or Stable mastery. Question Bank writes remain unauthorized.

Student-supervision/AI-agent product directions and resume updates are requested future discussions, not delivered features or substitutes for the publication gate.

## Work record

Work began at 10:01:22 CST.
Lunch: 13:11:55–14:25:10 CST, excluded duration 01:13:15.
The user changed the day to Normal and later removed the effective-time cap, while retaining 20:30 CST as the latest finish.
Final work accounting and delivery metadata belong in the canonical Issue and closure record; they are not inferred here.
