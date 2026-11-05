#!/bin/bash
# Host-side provisioning of the restricted application owner (D1 r4; r5 header note below). Normally called by
# scripts/task-evidence-demo.sh (stage "provision"); see docs/task-evidence-http-demo-runbook.md §4.
# Usage: scripts/task-evidence-demo-initdb.sh <app-password-file> <bootstrap-pgpass-file>   (DEMO_REPO exported)
#
# r5: the libpq boundary itself clears inherited PG* variables, verifies both files with fstat after opening
# and refuses secret locations inside the repo or an evidence directory; this wrapper adds no secret handling.
# r4 change: no self-made PBKDF2/HMAC/SCRAM code and no verifier in a shell variable or heredoc. The official
# libpq function PQencryptPasswordConn(conn, pw, user, "scram-sha-256") produces the verifier inside
# scripts/task-evidence-demo-libpq.py, which also runs the role statements over the same TCP connection.
# Only file paths are passed on argv; secret values never are. The helper prints one de-secreted JSON line.
set -uo pipefail; set +x
PWFILE=${1:?app password file}
PASSFILE=${2:?bootstrap pgpass file}
REPO=${DEMO_REPO:?DEMO_REPO not set}
for f in "$PWFILE" "$PASSFILE"; do
  case "$(readlink -f -- "$f")/" in "$REPO"/*) echo "secret files must be outside the repo" >&2; exit 3;; esac
done
timeout 60 python3 -I -B "$REPO/scripts/task-evidence-demo-libpq.py" provision \
  --app-password-file "$PWFILE" --bootstrap-passfile "$PASSFILE"
rc=$?
[ "$rc" = 0 ] && echo "provisioned" || echo "provision stopped rc=$rc (see the JSON line above; no automatic retry)" >&2
exit "$rc"
