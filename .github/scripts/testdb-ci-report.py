"""Scan captured CI test output for the job's generated secrets before anything is displayed or uploaded.

Fails closed: an unreadable/invalid secret, a missing required output, any read error or any hit prints no captured
output, prepares no artifact and exits non-zero. A clean scan displays the Maven log, copies the scanned Surefire
reports into the artifact directory with a label (passed / failure-evidence) and exits 0; the Maven status itself is
carried by the verify step and is never converted into success here. Secrets are read in-process and never printed.
"""
import os
import re
import shutil
import sys

def fail(code):
    print("TESTDB_SCAN_FAILED " + code)
    sys.exit(1)

temp = os.environ.get("RUNNER_TEMP", "")
if not temp or not os.path.isdir(temp) or os.path.islink(temp):
    fail("RUNNER_TEMP_INVALID")
work = os.path.join(temp, "jtm-ci")
out = os.path.join(work, "out")
reports = os.path.join(os.getcwd(), "target", "surefire-reports")
artifact = os.path.join(work, "artifact")

secrets = []
for name in ("admin", "app"):
    try:
        with open(os.path.join(work, "secrets", name), "rb") as handle:
            value = handle.read()
    except OSError:
        fail("SECRET_UNREADABLE")
    if not re.fullmatch(rb"[0-9a-f]{64}", value):
        fail("SECRET_INVALID")
    secrets.append(value)

try:
    with open(os.path.join(out, "maven.rc")) as handle:
        maven_rc = handle.read().strip()
except OSError:
    fail("MAVEN_RC_MISSING")
if not re.fullmatch(r"\d+", maven_rc):
    fail("MAVEN_RC_INVALID")

candidates = [os.path.join(out, "maven.log")]
if not os.path.isdir(reports) or os.path.islink(reports):
    fail("REPORTS_MISSING")
for entry in sorted(os.listdir(reports)):
    path = os.path.join(reports, entry)
    if os.path.islink(path) or not os.path.isfile(path):
        fail("REPORT_ENTRY_UNEXPECTED")
    candidates.append(path)
if not any(os.path.basename(p).startswith("TEST-") and p.endswith(".xml") for p in candidates):
    fail("XML_REPORTS_MISSING")

hits = 0
for path in candidates:
    try:
        with open(path, "rb") as handle:
            data = handle.read()
    except OSError:
        fail("READ_ERROR")
    hits += sum(data.count(secret) for secret in secrets)
print("TESTDB_SCAN files=%d hits=%d" % (len(candidates), hits))
if hits:
    fail("SECRET_HIT")

label = "passed" if maven_rc == "0" else "failure-evidence"
if os.path.exists(artifact):
    fail("ARTIFACT_DIR_EXISTS")
os.makedirs(os.path.join(artifact, "surefire-reports"), mode=0o700)
for path in candidates[1:]:
    shutil.copyfile(path, os.path.join(artifact, "surefire-reports", os.path.basename(path)))
with open(os.path.join(artifact, "LABEL.txt"), "w") as handle:
    handle.write("label=%s maven_rc=%s scanned_files=%d secret_hits=0\n" % (label, maven_rc, len(candidates)))

with open(os.path.join(out, "maven.log"), "rb") as handle:
    sys.stdout.flush()
    sys.stdout.buffer.write(handle.read())
sys.stdout.flush()

github_output = os.environ.get("GITHUB_OUTPUT")
if not github_output:
    fail("GITHUB_OUTPUT_MISSING")
with open(github_output, "a") as handle:
    handle.write("eligible=true\nlabel=%s\n" % label)
print("TESTDB_SCAN_CLEAN label=%s maven_rc=%s" % (label, maven_rc))
