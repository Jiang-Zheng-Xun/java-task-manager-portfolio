#!/bin/bash
# Task Evidence HTTP demo — single operator entry point. Demo-only. What was and was not verified, and the limits of
# each verification, are in docs/task-evidence-http-demo-runbook.md; nothing here claims general coverage.
#
#   scripts/task-evidence-demo.sh preflight      checks only: no secrets, no services, no files written
#   scripts/task-evidence-demo.sh run            full sequence (needs its own authorization)
#   scripts/task-evidence-demo.sh hold <RUN>     incident containment request: blocks further dispatch, stops this
#                                                run's verified operations and app, never removes anything
#   scripts/task-evidence-demo.sh stop <RUN>     close-out of an ended run (same removal rules and verdict as run)
#
# Exit codes (run, stop and hold use the same verdict, recomputed from current state every time): 0 PASS;
# 1 FAIL (everything this run created is confirmed removed); 2 PENDING/UNKNOWN (includes a hold whose stop is not
# confirmed); 3 STOPPED before secrets or services existed; 4 HELD (hold requested AND every stop confirmed); 64 refused.
# preflight and run must pass check_source_gate; hold and stop do not use it, so containment does not depend on the
# working tree. They keep their own run identity, path, lock, process and resource checks.
# Execute with bash as documented in the runbook. Sourcing is refused on the first command line; a refused
# source still returns non-zero to the caller (an errexit caller may exit) — it is not side-effect free.
if [[ "${BASH_SOURCE[0]:-}" != "$0" ]]; then echo "task-evidence-demo.sh must be executed with bash, not sourced" >&2; return 64; fi
set -uo pipefail; set +x            # no `set -e`: every result is captured explicitly; tracing stays off
umask 077

APP_BASELINE_COMMIT=6ce1ca5d277fa864c33e75ccfecfdafb3790741d   # accepted application source (pom.xml, src/main); not the commit of a run
RUN_HEAD=                                                      # actual HEAD of this invocation, set by check_source_gate
PROJECT=jtm-evidence-demo
ISO_ROOT=/home/logos/jtm-evidence-demo-20261102       # isolation root; evidence-* directories here never hold secrets
LOCKFILE="$ISO_ROOT/demo-fixed-resources.lock"       # ONE lock for the fixed project/ports, whatever RUNROOT is
LABEL=jtm.demo.run-id
ORIGIN=http://127.0.0.1:18080
DB_URL_FIXED="jdbc:postgresql://127.0.0.1:55433/task_evidence_demo"
PSQL=/usr/lib/postgresql/17/bin/psql                  # real binary (the /usr/bin/psql wrapper is not used)
SAFE_PATH='^/[A-Za-z0-9._/-]+$'
REPO=; ROOT=; RUN=; PRIV=; RUN_ID=; FINALIZED=no; FIXED_FD=no
CID=; VOL=; NET=; DRC=; OPF=; SECRET_ENV_FILE=; DISPATCH_MARK=; DB_ALL_ABSENT=no; REC_FAILED=no
COMPOSE_BASE=()
MAKE_SECRETS_PY='
import os, secrets, stat, sys
dfd = os.open(sys.argv[1], os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
st = os.fstat(dfd)
if st.st_uid != os.getuid() or stat.S_IMODE(st.st_mode) != 0o700: sys.exit(3)
def put(name, data):
    fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600, dir_fd=dfd)
    with os.fdopen(fd, "w") as f: f.write(data)
boot, app = secrets.token_hex(32), secrets.token_hex(32)
put("bootstrap_password", boot)
put("bootstrap_pgpass", "127.0.0.1:55433:task_evidence_demo:task_evidence_demo_bootstrap:%s\n" % boot)
put("app_password", app)
put("pgpass", "127.0.0.1:55433:task_evidence_demo:task_evidence_demo_owner:%s\n" % app)
'

say()  { printf '[%s] %s\n' "$(date +%T)" "$*"; }
rec()  {   # V5b: a failed status write is remembered (REC_FAILED) and returned, never hidden by say()
  if printf '%s\t%s\t%s\n' "$1" "$2" "$(date +%FT%T%z)" >> "$RUN/status.tsv"; then say "$1: $2"; return 0; fi
  REC_FAILED=yes; echo "STATUS_RECORD_WRITE_FAILED: $1" >&2; return 1; }
last() { awk -F'\t' -v s="$1" '$1==s{v=$2} END{print v}' "$RUN/status.tsv" 2>/dev/null; }
refuse() { say "REFUSED: $*"; exit 64; }
set_compose() {
  COMPOSE_BASE=(env -u COMPOSE_FILE -u COMPOSE_PROJECT_NAME -u COMPOSE_PROFILES -u COMPOSE_ENV_FILES "DEMO_RUN_ID=$RUN_ID"
                docker compose --project-name "$PROJECT" --file "$REPO/scripts/task-evidence-demo-compose.yaml" --env-file /dev/null)
}
# timeout always wraps a real executable (env / docker), never a shell function
dc() { local t=$1; shift; timeout "$t" env -u DEMO_BOOTSTRAP_PASSWORD "${COMPOSE_BASE[@]}" "$@"; }
dk() { local t=$1; shift; timeout "$t" docker "$@"; }
# ---- H1: process status is RUNNING | ENDED | UNKNOWN (never "not alive" => ended) -------------------------
valid_pid()   { [[ "${1:-}" =~ ^[1-9][0-9]{0,9}$ ]]; }
valid_start() { [[ "${1:-}" =~ ^[0-9]{1,20}$ ]]; }
proc_read() {   # pid -> "state starttime"; rc 1 = not readable, rc 2 = unparsable
  local line rest; local -a f
  IFS= read -r line 2>/dev/null < "/proc/$1/stat" || return 1
  [[ "$line" == *") "* ]] || return 2
  rest=${line##*) }; read -r -a f <<< "$rest"
  [[ "${f[0]:-}" =~ ^[A-Za-z]$ ]] && valid_start "${f[19]:-}" || return 2
  printf '%s %s\n' "${f[0]}" "${f[19]}"
}
starttime() { local o; o=$(proc_read "$1") && printf '%s' "${o#* }"; }
proc_status() {   # pid start -> RUNNING | ENDED | UNKNOWN ; one read is a snapshot, not race-free
  valid_pid "${1:-}" && valid_start "${2:-}" || { echo UNKNOWN; return; }
  local out st t
  if out=$(proc_read "$1"); then
    read -r st t <<< "$out"
    if [ "$t" != "$2" ]; then echo ENDED                       # PID now belongs to another process: the recorded one is gone
    elif [ "$st" = Z ] || [ "$st" = X ]; then echo ENDED       # exited, not yet reaped (says nothing about descendants)
    else echo RUNNING; fi
  elif [ $? = 1 ] && proc_read "$$" > /dev/null && [ ! -e "/proc/$1" ]; then echo ENDED   # /proc works and the PID is absent
  else echo UNKNOWN; fi                                        # unreadable, unparsable, or present but unreadable
}
mode_ok() { [ "$(stat -c %u -- "$1")" = "$(id -u)" ] && [ $(( 8#$(stat -c %a -- "$1") & 8#077 )) = 0 ]; }

locate_repo() {
  local here; here=$(dirname -- "$(realpath -e -- "${BASH_SOURCE[0]}")") || return 1
  REPO=$(git -C "$here/.." rev-parse --show-toplevel) && REPO=$(realpath -e -- "$REPO") && cd "$REPO" || return 1
}

# ---- source gate (preflight and run only; never hold/stop) ---------------------------------------------------
# Accepts any committed HEAD that descends from APP_BASELINE_COMMIT and whose pom.xml and src/main, both as committed
# and as they are on disk, equal the baseline, in a working tree with nothing uncommitted. Every failed or unclear
# check refuses. Ignored files outside src/main (target/) do not refuse. A point-in-time check of the listed items,
# not tamper protection: it cannot stop another process changing files afterwards, and it does not cover git itself.
gq() { GIT_OPTIONAL_LOCKS=0 git -c core.fsmonitor=false "$@"; }
gate_no() { say "source gate refused: $*"; return 1; }
check_source_gate() {
  local head a b p out line meta mode type oid got
  head=$(gq rev-parse --verify --quiet 'HEAD^{commit}') && [[ "$head" =~ ^[0-9a-f]{40}$ ]] || { gate_no "HEAD is not a valid commit"; return 1; }
  gq cat-file -e "$APP_BASELINE_COMMIT^{commit}" 2>/dev/null || { gate_no "application baseline commit not found"; return 1; }
  gq merge-base --is-ancestor "$APP_BASELINE_COMMIT" "$head" 2>/dev/null || { gate_no "application baseline is not an ancestor of HEAD (or the check failed)"; return 1; }
  for p in pom.xml src/main; do
    a=$(gq rev-parse --verify --quiet "$APP_BASELINE_COMMIT:$p") && b=$(gq rev-parse --verify --quiet "$head:$p") && [ -n "$a" ] && [ "$a" = "$b" ] \
      || { gate_no "committed $p differs from the application baseline (or could not be read)"; return 1; }
  done
  out=$(gq ls-files -v) || { gate_no "index listing failed"; return 1; }
  grep -qv '^H ' <<< "$out"
  case $? in
    1) ;;
    0) gate_no "index has entries with hidden or unusual state (assume-unchanged, skip-worktree, unmerged)"; return 1 ;;
    *) gate_no "index listing could not be checked"; return 1 ;;
  esac
  out=$(find -P pom.xml src/main ! -type f ! -type d -print -quit) || { gate_no "pom.xml or src/main could not be scanned"; return 1; }
  [ -z "$out" ] || { gate_no "unsupported file type under pom.xml or src/main"; return 1; }
  out=$(gq status --porcelain=v1 --ignored=matching --untracked-files=all -- pom.xml src/main) || { gate_no "status of application source failed"; return 1; }
  [ -z "$out" ] || { gate_no "pom.xml or src/main has changed, extra or ignored files"; return 1; }
  out=$(gq ls-tree -r "$APP_BASELINE_COMMIT" -- pom.xml src/main) && [ -n "$out" ] || { gate_no "baseline source listing failed"; return 1; }
  while IFS= read -r line; do
    meta=${line%%$'\t'*}; p=${line#*$'\t'}; read -r mode type oid <<< "$meta"
    [ "$type" = blob ] && [[ "$p" != \"* ]] || { gate_no "unsupported baseline entry"; return 1; }
    case "$mode" in 100644|100755) ;; *) gate_no "unsupported baseline file mode: $p"; return 1 ;; esac
    [ -f "$p" ] && [ ! -L "$p" ] || { gate_no "application file missing or not a regular file: $p"; return 1; }
    if [ "$mode" = 100755 ]; then [ -x "$p" ]; else [ ! -x "$p" ]; fi || { gate_no "application file mode differs: $p"; return 1; }
    got=$(gq hash-object --no-filters -- "$p") && [ "$got" = "$oid" ] || { gate_no "application file content differs: $p"; return 1; }
  done <<< "$out"
  out=$(gq status --porcelain=v1 --untracked-files=all) || { gate_no "working tree status failed"; return 1; }
  [ -z "$out" ] || { gate_no "working tree has uncommitted or untracked files"; return 1; }
  RUN_HEAD=$head
}

# ---- paths (before any write) ------------------------------------------------------------------------------
dir_chain_safe() {    # every existing ancestor: real dir, owner root or me, not group/other-writable unless sticky
  local p=$1 o m
  while :; do
    [ -d "$p" ] && [ ! -L "$p" ] || return 1
    o=$(stat -c %u -- "$p") && m=$(stat -c %a -- "$p") || return 1
    { [ "$o" = 0 ] || [ "$o" = "$(id -u)" ]; } || return 1
    if (( 8#$m & 8#022 )) && ! (( 8#$m & 8#1000 )); then return 1; fi
    [ "$p" = / ] && return 0; p=$(dirname -- "$p")
  done
}
validate_root() {
  local in=${RUNROOT:-"$HOME/.local/state/$PROJECT"} a iso
  [[ "$in" =~ $SAFE_PATH ]] || refuse "RUNROOT must be absolute and use only [A-Za-z0-9._/-]"
  ROOT=$(realpath -m -- "$in") || refuse "RUNROOT cannot be normalized"     # resolves .., symlinks of existing parts
  [[ "$ROOT" =~ $SAFE_PATH ]] || refuse "normalized RUNROOT has unsupported characters"
  iso=$(realpath -m -- "$ISO_ROOT")
  case "$ROOT/" in "$REPO"/*) refuse "RUNROOT is inside the repository";; esac
  case "$REPO/" in "$ROOT"/*) refuse "RUNROOT must not contain the repository";; esac
  case "$ROOT/" in "$iso"/evidence-*) refuse "RUNROOT is inside an evidence directory";; esac
  a=$ROOT; while [ ! -e "$a" ]; do a=$(dirname -- "$a"); done
  dir_chain_safe "$a" || refuse "unsafe owner/permissions on $a or a parent (nothing changed)"
  if [ -e "$ROOT" ]; then
    { [ -d "$ROOT" ] && [ ! -L "$ROOT" ] && mode_ok "$ROOT"; } || refuse "RUNROOT exists but is not a private (0700) directory of this user (not changed)"
  fi
}

# ---- G1: fixed lock (kernel flock on fd 9) -------------------------------------------------------------------
# fd 9 is inherited by every process this script starts (dispatched operations, the app, read-only queries);
# they share ONE open file description, so the lock stays held while any of them still has the descriptor open.
# This script NEVER calls `flock -u` on fd 9 (an explicit LOCK_UN would drop the lock for every sharer). It only
# closes its own descriptor (`exec 9>&-`) or exits; the kernel releases the lock when the last sharer closes it.
# Processes that close inherited descriptors (e.g. curl started by Python subprocess, children of Go/Java CLIs)
# are NOT covered; therefore a released lock never by itself proves that every descendant has ended.
open_fixed_lock() {
  dir_chain_safe "$ISO_ROOT" || refuse "unsafe isolation root permissions (not changed)"
  [ ! -L "$LOCKFILE" ] || refuse "lock path is a symlink"
  exec 9>>"$LOCKFILE" || refuse "cannot open $LOCKFILE"
  { [ -f "$LOCKFILE" ] && [ ! -L "$LOCKFILE" ] && mode_ok "$LOCKFILE" &&
    [ "$(stat -L -c '%d:%i' "/proc/$$/fd/9")" = "$(stat -c '%d:%i' -- "$LOCKFILE")" ]; } \
    || refuse "lock file is not a private regular file of this user (not changed)"
  FIXED_FD=yes
}
take_fixed_lock() {   # $1 role, $2 wait seconds (0 = never wait). Never removes, replaces or takes over a holder.
  open_fixed_lock
  if [ "${2:-0}" = 0 ]; then flock -n 9 || return 1; else flock -w "$2" 9 || return 1; fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$(date +%FT%T%z)" "$1" "${RUN_ID:--}" "$$" "$(starttime $$)" >&9   # owner log only
}
close_fixed_fd() { [ "$FIXED_FD" = yes ] && { exec 9>&-; FIXED_FD=no; }; return 0; }   # close only; never LOCK_UN

# ---- per-run control lock (fd 8): HOLD check, op registration and dispatch are one critical section ---------
open_ctl() {
  [ ! -L "$RUN/control.lock" ] || refuse "control.lock is a symlink"
  exec 8>>"$RUN/control.lock" || refuse "cannot open control.lock"; mode_ok "$RUN/control.lock" || refuse "control.lock not private"
  [ -d "$RUN/ops" ] || mkdir -m 700 -- "$RUN/ops" || refuse "cannot create ops records"
}
ctl_lock()   { flock -w 10 8; }
ctl_unlock() { flock -u 8; }          # fd 8 is never inherited by children (closed with 8>&-), so unlocking is local
held()       { [ -f "$RUN/HOLD" ]; }
main_status() { local p= s=; read -r p s < "$RUN/main.pid" 2>/dev/null; proc_status "$p" "$s"; }

# ---- G2: one record per operation, never overwritten ---------------------------------------------------------
# $RUN/ops/NNN-<stage>.op : line 1 "meta<TAB>stage= class= pid= start=", then appended events:
#   "ended<TAB>rc=N", "signaled<TAB>by=WHO", "settle<TAB>STATE". Records are never deleted or rewritten.
# Each operation also has NNN-<stage>.lock, flocked by the operation's own subshell on fd 7 before `exec timeout`;
# the command and every descendant that keeps fd 7 open keep that lock held.
# class inherit   = by source review, the command and all its children keep inherited descriptors
# class may-close = the command may start processes that close inherited descriptors (cannot be verified)
op_meta()   { awk -F'\t' '$1=="meta"{print $2; exit}' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p"; }
op_has()    { grep -q "^$2"$'\t' "$1" 2>/dev/null; }
op_settle() { awk -F'\t' '$1=="settle"{v=$2} END{print v}' "$1"; }
op_rc()     { awk -F'\t' '$1=="ended"{v=$2} END{sub(/^rc=/,"",v); print v}' "$1"; }
new_op() {    # $1 stage; called under the control lock; exclusive create, sequence never reused
  local n; n=$(find "$RUN/ops" -maxdepth 1 -name '*.op' | wc -l); n=$((n+1))
  OPF=$(printf '%s/ops/%03d-%s.op' "$RUN" "$n" "$1")
  ( set -C; : > "$OPF" ) 2>/dev/null && ( set -C; : > "${OPF%.op}.lock" ) 2>/dev/null
}
op_lock_state() {   # FREE | HELD | UNKNOWN (a fresh descriptor; conflict exit code 75 is distinguished from errors)
  local lk="${1%.op}.lock" r
  [ -f "$lk" ] && [ ! -L "$lk" ] || { echo UNKNOWN; return; }
  ( exec 6< "$lk" && flock -n -E 75 6 ) 2>/dev/null; r=$?
  case "$r" in 0) echo FREE ;; 75) echo HELD ;; *) echo UNKNOWN ;; esac
}
settle_op() {   # recompute; append only when the state changes; true only for CONFIRMED or NOT_STARTED
  local f=$1 pid st cls rc s ps lk
  pid=$(op_meta "$f" pid); st=$(op_meta "$f" start); cls=$(op_meta "$f" class); rc=$(op_rc "$f"); lk=$(op_lock_state "$f")
  if ! op_has "$f" go && op_has "$f" ended && [ "$lk" = FREE ]; then
    s=NOT_STARTED                       # no go was ever written, the child's exit was observed by wait, no lock holder
  elif ! op_has "$f" meta || ! valid_pid "$pid" || ! valid_start "$st" || ! [[ "$cls" =~ ^(inherit|may-close)$ ]]; then
    s="UNKNOWN (handoff incomplete or identity missing/invalid)"
  else
    ps=$(proc_status "$pid" "$st")
    case "$ps" in
      RUNNING) s=RUNNING ;;
      ENDED)
        if [ "$lk" = HELD ]; then s="UNKNOWN (a process still holds the operation lock)"
        elif [ "$lk" != FREE ]; then s="UNKNOWN (operation lock state unreadable)"
        elif ! op_has "$f" go; then s="UNKNOWN (go not written and end not observed)"
        elif op_has "$f" signaled || [ -z "$rc" ] || [ "$rc" = 124 ] || { [[ "$rc" =~ ^[0-9]+$ ]] && [ "$rc" -ge 128 ]; }; then
          if [ "$cls" = inherit ]; then s=CONFIRMED
          else s="UNKNOWN (terminated or end not observed; descendants that close inherited descriptors cannot be verified)"; fi
        else s=CONFIRMED; fi ;;
      *) s="UNKNOWN (process state unreadable)" ;;
    esac
  fi
  [ "$(op_settle "$f")" = "$s" ] || printf 'settle\t%s\t%s\n' "$s" "$(date +%FT%T%z)" >> "$f"
  [ "$s" = CONFIRMED ] || [ "$s" = NOT_STARTED ]
}
ops_all_settled() { local f r=0; for f in "$RUN"/ops/*.op; do [ -e "$f" ] || continue; settle_op "$f" || r=1; done; return "$r"; }
signal_op() {   # $1 op file, $2 who: TERM only when the recorded identity is valid AND the process is RUNNING
  local pid st; pid=$(op_meta "$1" pid); st=$(op_meta "$1" start)
  [ "$(proc_status "$pid" "$st")" = RUNNING ] || return 0
  if kill -TERM "$pid" 2>/dev/null; then printf 'signaled\tby=%s\t%s\n' "$2" "$(date +%FT%T%z)" >> "$1"
  else printf 'signal_failed\tby=%s\t%s\n' "$2" "$(date +%FT%T%z)" >> "$1"; fi
}
signal_unsettled() {   # $1 who; every recorded op/app root that is still alive
  local f; for f in "$RUN"/ops/*.op; do [ -e "$f" ] || continue
    [ "$(op_meta "$f" stage)" = db_stop ] && continue      # never interrupt a containment stop
    settle_op "$f" || signal_op "$f" "$1"; done
}
wait_settled() { local i; for i in $(seq "$1"); do ops_all_settled && return 0; sleep 1; done; ops_all_settled; }

# dispatch CLASS STAGE TREE TIMEOUT OUTFILE argv...  — every state-changing external command goes through here.
#   CLASS normal: refused once a hold is requested; CLASS contain: allowed during a hold (DB stop only).
#   Under the control lock: check HOLD, create a new op record, start `timeout` in the background (fd 8 closed,
#   op lock taken on fd 7 by the child), record pid + start time, release. Sets DRC: rc | held | control.
dispatch() {
  local cls=$1 stage=$2 tree=$3 t=$4 out=$5 pid st; shift 5; DRC=; OPF=
  ctl_lock || { DRC=control; rec "$stage" "NOT_STARTED (control lock not obtained; no record)"; return 1; }
  if [ "$cls" = normal ] && held; then ctl_unlock; DRC=held; rec "$stage" "NOT_STARTED (hold requested; no record)"; return 1; fi
  new_op "$stage" || { ctl_unlock; DRC=control; rec "$stage" "NOT_STARTED (op record could not be created)"; return 1; }
  printf 'created\t%s\n' "$(date +%FT%T%z)" >> "$OPF"
  # H3 handoff: the PARENT takes the op lock before the child exists, so a free lock can never mean "not yet locked"
  exec 7>> "${OPF%.op}.lock" && flock -n -E 75 7 \
    || { exec 7>&-; ctl_unlock; DRC=control; rec "$stage" "NOT_STARTED (op lock not obtained; record kept)"; return 1; }
  printf 'locked\t%s\n' "$(date +%FT%T%z)" >> "$OPF"
  [ -n "$DISPATCH_MARK" ] && : > "$DISPATCH_MARK"
  ( op=$OPF; i=0                       # child: inherits fd 7 (shared lock), closes fd 8, waits for "go" before any work
    until grep -q $'^go\t' "$op" 2>/dev/null; do i=$((i+1)); [ "$i" -le 100 ] || exit 125; sleep 0.1; done
    if [ -n "$SECRET_ENV_FILE" ]; then DEMO_BOOTSTRAP_PASSWORD=$(cat "$SECRET_ENV_FILE"); export DEMO_BOOTSTRAP_PASSWORD; fi
    exec timeout "$t" "$@" ) >> "$out" 2>&1 8>&- &
  pid=$!; st=$(starttime "$pid")
  printf 'meta\tstage=%s class=%s pid=%s start=%s\n' "$stage" "$tree" "$pid" "${st:-unknown}" >> "$OPF"
  if valid_pid "$pid" && valid_start "$st"; then printf 'go\t%s\n' "$(date +%FT%T%z)" >> "$OPF"
  else printf 'handoff_failed\tidentity not recorded; go withheld\t%s\n' "$(date +%FT%T%z)" >> "$OPF"; fi
  exec 7>&-                            # close the parent's copy only (no unlock): the child keeps the lock
  ctl_unlock
  wait "$pid"; DRC=$?
  printf 'ended\trc=%s\t%s\n' "$DRC" "$(date +%FT%T%z)" >> "$OPF"
  op_has "$OPF" go || DRC=control
  settle_op "$OPF"
  return 0
}

# ---- resources: recorded IDs, per-resource classification ---------------------------------------------------
record_resources() {   # after `up` (also after a failed `up`): whatever can be identified exactly; never guessed
  local c0 cid=unknown vol=unknown net=unknown
  c0=$(dc 20 ps -a -q postgres) && [ "$(printf '%s\n' "$c0" | grep -c .)" = 1 ] && cid=$(dk 10 inspect -f '{{.Id}}' "$c0") || cid=unknown
  if [[ "$cid" =~ ^[0-9a-f]{64}$ ]]; then
    vol=$(dk 10 inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' "$cid") || vol=unknown
    net=$(dk 10 inspect -f '{{range .NetworkSettings.Networks}}{{.NetworkID}}{{end}}' "$cid") || net=unknown
  else cid=unknown; fi
  [[ "$vol" =~ ^[A-Za-z0-9_.-]+$ ]] || vol=unknown; [[ "$net" =~ ^[0-9a-f]{64}$ ]] || net=unknown
  printf 'container=%s\nvolume=%s\nnetwork=%s\n' "$cid" "$vol" "$net" > "$RUN/resources.txt"
  load_resources
  [ "$CID" != unknown ] && [ "$VOL" != unknown ] && [ "$NET" != unknown ] &&
  [ "$(classify container "$CID")" = OWNED ] && [ "$(classify volume "$VOL")" = OWNED ] && [ "$(classify network "$NET")" = OWNED ]
}
load_resources() {
  CID=$(sed -n 's/^container=//p' "$RUN/resources.txt" 2>/dev/null); VOL=$(sed -n 's/^volume=//p' "$RUN/resources.txt" 2>/dev/null)
  NET=$(sed -n 's/^network=//p' "$RUN/resources.txt" 2>/dev/null)
  CID=${CID:-unknown}; VOL=${VOL:-unknown}; NET=${NET:-unknown}
}
classify() {   # kind id -> ABSENT | OWNED | FOREIGN | UNKNOWN  (a failed query is never ABSENT)
  local kind=$1 id=$2 l rc lab
  [ "$id" != unknown ] && [ -n "$id" ] || { echo UNKNOWN; return; }
  case "$kind" in
    container) l=$(dk 10 ps -a -q --no-trunc); rc=$? ;;
    volume)    l=$(dk 10 volume ls -q); rc=$? ;;
    network)   l=$(dk 10 network ls -q --no-trunc); rc=$? ;;
  esac
  [ "$rc" = 0 ] || { echo UNKNOWN; return; }
  printf '%s\n' "$l" | grep -qxF -- "$id" || { echo ABSENT; return; }
  case "$kind" in
    container) lab=$(dk 10 inspect -f "{{index .Config.Labels \"$LABEL\"}}" "$id"); rc=$? ;;
    volume)    lab=$(dk 10 volume inspect -f "{{index .Labels \"$LABEL\"}}" "$id"); rc=$? ;;
    network)   lab=$(dk 10 network inspect -f "{{index .Labels \"$LABEL\"}}" "$id"); rc=$? ;;
  esac
  [ "$rc" = 0 ] || { echo UNKNOWN; return; }
  [ "$lab" = "$RUN_ID" ] && echo OWNED || echo FOREIGN
}
db_state() {   # NOT_CREATED | ABSENT | STOPPED | RUNNING | UNKNOWN  (read-only)
  [ -f "$RUN/db-up-attempted" ] || { echo NOT_CREATED; return; }
  load_resources; local c r; c=$(classify container "$CID")
  case "$c" in
    ABSENT) echo ABSENT ;;
    OWNED)  r=$(dk 10 inspect -f '{{.State.Running}}' "$CID"); case "$r" in false) echo STOPPED;; true) echo RUNNING;; *) echo UNKNOWN;; esac ;;
    *)      echo UNKNOWN ;;
  esac
}
remove_one() {   # kind id argv... ; true only when the resource is confirmed absent afterwards
  local kind=$1 id=$2 c d; shift 2
  c=$(classify "$kind" "$id")
  case "$c" in
    ABSENT) rec "remove_$kind" "ABSENT (already gone)"; return 0 ;;
    FOREIGN|UNKNOWN) rec "remove_$kind" "$c (not touched)"; return 1 ;;
  esac
  dispatch normal "remove_$kind" may-close 60 "$RUN/ops.log" "$@"; d=$DRC
  [ "$d" = held ] && { rec "remove_$kind" "NOT_RUN (hold requested)"; return 1; }
  c=$(classify "$kind" "$id"); rec "remove_$kind" "rc=$d after=$c"
  [ "$c" = ABSENT ]
}

# ---- preflight: capability checks before any secret or service (read-only) --------------------------------
preflight() {   # $1 = run dir or empty (preflight-only mode writes nothing)
  local out=${1:-} ok=yes t pj prc pl pv jv k ids=""
  for t in bash git python3 curl java timeout ss mvn docker awk sed ldd realpath stat flock find; do
    command -v "$t" > /dev/null || { say "missing tool: $t"; ok=no; }; done
  [ -x "$PSQL" ] || { say "missing $PSQL"; ok=no; }
  [ "$ok" = yes ] || return 3
  pj=$(timeout 30 python3 -I -B "$REPO/scripts/task-evidence-demo-libpq.py" preflight); prc=$?
  say "python libpq preflight rc=$prc $pj"; [ -n "$out" ] && printf '%s\n' "$pj" > "$out/libpq-python.json"
  [ "$prc" = 0 ] || return 3
  [ "$(stat -c %u "$PSQL")" = 0 ] || { say "psql binary not root-owned"; return 3; }
  pl=$(timeout 10 ldd "$PSQL" | awk '$1 ~ /^libpq\.so/ {print $3}') || { say "ldd failed or timed out"; return 3; }
  [ -n "$pl" ] && [ "$(realpath -e -- "$pl")" = "$(realpath -e /usr/lib/x86_64-linux-gnu/libpq.so.5)" ] \
    || { say "psql loads an unexpected or unresolved libpq: stop"; return 3; }
  pv=$(timeout 10 "$PSQL" --version) || { say "psql --version failed"; return 3; }
  jv=$(sed -n 's:.*<postgresql.version>\(.*\)</postgresql.version>.*:\1:p' "$REPO/pom.xml")
  say "psql: $pv; psql libpq: $pl; pgJDBC (pom): ${jv:-unknown}"; [ -n "$jv" ] || return 3
  timeout 20 docker compose version > /dev/null || { say "docker compose v2 unavailable"; return 3; }
  for k in "ps -a" "volume ls" "network ls"; do
    ids+=$(timeout 20 docker $k -q --filter label=com.docker.compose.project=$PROJECT) || { say "docker query failed"; return 3; }; done
  [ -z "$ids" ] || { say "existing $PROJECT resources: stop (never taken over)"; return 3; }
  for k in 55433 18080; do
    t=$(timeout 10 ss -ltnH "sport = :$k") || { say "ss failed"; return 3; }
    [ -z "$t" ] || { say "port $k busy: stop"; return 3; }; done
  [ -n "$out" ] && printf 'psql=%s\npsql_libpq=%s\npgjdbc=%s\n' "$pv" "$pl" "$jv" > "$out/versions.txt"
  return 0
}

# ---- G3: secret files: every create/delete is a dispatched operation (HOLD-coordinated) ----------------------
secret_rm() {   # $1 stage, $2 path(s) via rm; records the observed state afterwards (never claims recoverability)
  local stage=$1; shift
  dispatch normal "$stage" inherit 30 "$RUN/ops.log" rm -r -f -- "$@"; local d=$DRC p left=""
  [ "$d" = held ] && { rec "$stage" "NOT_RUN (hold requested; files kept)"; return 1; }
  for p in "$@"; do [ -e "$p" ] && left+=" $(basename -- "$p")"; done
  if [ -z "$left" ]; then rec "$stage" "DELETED rc=$d"; return 0; fi
  rec "$stage" "PARTIAL_OR_FAILED rc=$d still_present:${left} (deleted parts are not recoverable)"; return 1
}

identity_check() {   # dispatched (normal, inherit); stdout+stderr together must equal the single expected line
  printf '%s\n' 'task_evidence_demo|task_evidence_demo_owner|task_evidence_demo_owner|UTF8|task_evidence_demo_owner|task_evidence_demo_owner|f|f|f|f|f|0|5s' \
    > "$RUN/identity-expected.txt"
  local conn="host=127.0.0.1 port=55433 dbname=task_evidence_demo user=task_evidence_demo_owner sslmode=disable gssencmode=disable require_auth=scram-sha-256 connect_timeout=5 passfile='$PRIV/pgpass' options='-c statement_timeout=5s'"
  dispatch normal identity inherit 20 "$RUN/identity-actual.txt" env -i PATH=/usr/bin:/bin LC_ALL=C "$PSQL" "$conn" -X -A -t -q -v ON_ERROR_STOP=1 \
    -c "SELECT current_database(), current_user, session_user, pg_encoding_to_char(d.encoding), pg_get_userbyid(d.datdba), (SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'public'), r.rolsuper, r.rolcreatedb, r.rolcreaterole, r.rolreplication, r.rolbypassrls, (SELECT count(*) FROM pg_auth_members m WHERE m.member = r.oid), current_setting('statement_timeout') FROM pg_database d, pg_roles r WHERE d.datname = current_database() AND r.rolname = current_user"
  echo "$DRC" > "$RUN/identity-rc.txt"
  [ "$DRC" = 0 ] && cmp -s "$RUN/identity-expected.txt" "$RUN/identity-actual.txt"
}

start_app() {   # same handoff as dispatch(); class inherit (src/main starts no processes)
  local jar pid st; jar=$(ls "$REPO"/target/java-task-manager-portfolio-*.jar 2>/dev/null | grep -v -- -plain)
  [ "$(printf '%s\n' "$jar" | grep -c .)" = 1 ] || return 1
  printf '%s\n' "$jar" > "$RUN/app.jar"
  ctl_lock || return 1
  if held; then ctl_unlock; return 2; fi
  new_op app || { ctl_unlock; return 1; }
  printf 'created\t%s\n' "$(date +%FT%T%z)" >> "$OPF"
  exec 7>> "${OPF%.op}.lock" && flock -n -E 75 7 || { exec 7>&-; ctl_unlock; return 1; }
  printf 'locked\t%s\n' "$(date +%FT%T%z)" >> "$OPF"
  ( op=$OPF; i=0
    until grep -q $'^go\t' "$op" 2>/dev/null; do i=$((i+1)); [ "$i" -le 100 ] || exit 125; sleep 0.1; done
    cd "$RUN" || exit 1
    unset $(compgen -e | grep -E '^(SPRING_|DB_|SERVER_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$|JAVA_OPTS$|LOGGING_|MANAGEMENT_|PG)')
    export DB_URL="$DB_URL_FIXED" DB_USERNAME=task_evidence_demo_owner SERVER_ADDRESS=127.0.0.1 SERVER_PORT=18080
    DB_PASSWORD=$(cat "$PRIV/app_password"); export DB_PASSWORD
    exec java "-D$LABEL=$RUN_ID" -jar "$jar" --spring.config.location=classpath:/application.yml ) > "$RUN/app.log" 2>&1 8>&- &
  pid=$!; st=$(starttime "$pid")
  printf 'meta\tstage=app class=inherit pid=%s start=%s\n' "$pid" "${st:-unknown}" >> "$OPF"
  printf '%s\n' "$OPF" > "$RUN/app.op"
  if valid_pid "$pid" && valid_start "$st"; then printf 'go\t%s\n' "$(date +%FT%T%z)" >> "$OPF"
  else printf 'handoff_failed\tidentity not recorded; go withheld\t%s\n' "$(date +%FT%T%z)" >> "$OPF"; exec 7>&-; ctl_unlock; return 1; fi
  exec 7>&-
  ctl_unlock
  # Readiness (V4d): GET /api/tasks/9223372036854775807 must answer exactly 404 with curl exit 0. Source contract:
  # GetTaskByIdService rejects id <= 0 with IllegalArgumentException (-> 400), so id 0 can never be ready; a positive id
  # that does not exist goes through the repository (DB) and TaskNotFoundException -> GlobalExceptionHandler 404.
  # Every attempt is logged in $RUN/readiness.tsv as numbers/fixed values only; a failed log write stops polling.
  local deadline=$((SECONDS+90)) code crc ps http n=0
  while [ $SECONDS -lt $deadline ]; do
    held && return 2
    n=$((n+1)); ps=$(proc_status "$pid" "$st")
    if [ "$ps" != RUNNING ]; then
      printf '%s\t%s\t-\tNO_HTTP\t%s\tstop_process_not_running\n' "$n" "$(date +%FT%T%z)" "$ps" >> "$RUN/readiness.tsv"; return 1; fi
    code=$(curl -q -s --noproxy '*' --max-redirs 0 --connect-timeout 2 --max-time 5 -H 'Accept: application/json' \
      -o /dev/null -w '%{http_code}' "$ORIGIN/api/tasks/9223372036854775807"); crc=$?
    if [[ "$code" =~ ^[1-5][0-9][0-9]$ ]]; then http=$code; else http=NO_HTTP; fi
    if [ "$crc" = 0 ] && [ "$http" = 404 ]; then
      printf '%s\t%s\t%s\t%s\t%s\tready\n' "$n" "$(date +%FT%T%z)" "$crc" "$http" "$ps" >> "$RUN/readiness.tsv" || return 1
      return 0; fi
    printf '%s\t%s\t%s\t%s\t%s\tnot_ready\n' "$n" "$(date +%FT%T%z)" "$crc" "$http" "$ps" >> "$RUN/readiness.tsv" || return 1
    sleep 2; done
  return 1
}
app_is_ours() {   # valid identity + RUNNING + exact jar + run-id argument (cmdline unreadable => not ours)
  local f pid st; f=$(cat "$RUN/app.op" 2>/dev/null) && [ -f "$f" ] || return 1
  pid=$(op_meta "$f" pid); st=$(op_meta "$f" start)
  [ "$(proc_status "$pid" "$st")" = RUNNING ] || return 1
  tr '\0' '\n' < "/proc/$pid/cmdline" 2>/dev/null | grep -qxF -- "$(cat "$RUN/app.jar")" &&
  tr '\0' '\n' < "/proc/$pid/cmdline" 2>/dev/null | grep -qxF -- "-D$LABEL=$RUN_ID"
}
stop_app() {
  local f ps; f=$(cat "$RUN/app.op" 2>/dev/null)
  if [ -z "$f" ] || [ ! -f "$f" ]; then rec app_stop not-started; return; fi
  ps=$(proc_status "$(op_meta "$f" pid)" "$(op_meta "$f" start)")
  case "$ps" in
    RUNNING) if app_is_ours; then signal_op "$f" stop_app; else rec app_stop identity-mismatch-not-signalled; return; fi ;;
    UNKNOWN) settle_op "$f"; rec app_stop "UNKNOWN ($(op_settle "$f"); not signalled)"; return ;;
  esac
  local i; for i in $(seq 30); do settle_op "$f" && { rec app_stop "$( [ "$(op_settle "$f")" = NOT_STARTED ] && echo not-started || echo yes )"; return; }; sleep 1; done
  rec app_stop "UNKNOWN ($(op_settle "$f"); no SIGKILL)"
}
stop_db() {   # containment class: allowed during a hold; only an OWNED running container is stopped
  local s; s=$(db_state)
  case "$s" in
    RUNNING) load_resources; dispatch contain db_stop may-close 60 "$RUN/ops.log" docker stop -t 30 -- "$CID"
             s=$(db_state); rec db_stop "$s rc=$DRC" ;;
    *)       rec db_stop "$s" ;;
  esac
}
remove_db() {   # per resource; already-absent items count as done; unknown/foreign are never touched
  DB_ALL_ABSENT=no
  [ -f "$RUN/db-up-attempted" ] || { rec db_remove NOT_CREATED; DB_ALL_ABSENT=yes; return; }
  load_resources
  if [ "$CID" = unknown ]; then rec db_remove "UNKNOWN (container ID not recorded; nothing guessed)"; return; fi
  local ok=yes
  if remove_one container "$CID" docker rm -- "$CID"; then
    if [ "$VOL" = unknown ]; then rec remove_volume "UNKNOWN (not recorded)"; ok=no
    else remove_one volume "$VOL" docker volume rm -- "$VOL" || ok=no; fi
    if [ "$NET" = unknown ]; then rec remove_network "UNKNOWN (not recorded)"; ok=no
    else remove_one network "$NET" docker network rm -- "$NET" || ok=no; fi
  else ok=no; rec remove_volume "NOT_RUN (container not confirmed absent)"; rec remove_network "NOT_RUN (container not confirmed absent)"; fi
  if [ "$ok" = yes ]; then DB_ALL_ABSENT=yes; rec db_remove PASS; else rec db_remove "PENDING (see remove_* lines)"; fi
}
removal_allowed() {
  ! held && [ -z "$(last interrupted)" ] && ops_all_settled &&
  [[ "$(last app_stop)" =~ ^(yes|not-started)$ ]] && [[ "$(last db_stop)" =~ ^(STOPPED|ABSENT|NOT_CREATED) ]] &&
  { [ "$(last demo)" = rc=0 ] || [ "$(last cleanup)" = rc=0 ] || [ ! -f "$RUN/app.op" ]; }
}

finalize() {   # $1 = close | hold ; resource operations require the fixed lock
  [ "$FINALIZED" = yes ] && return; FINALIZED=yes
  if [ "$REC_FAILED" = yes ]; then set -- hold "status record write failed"; fi   # V5b: never remove after a record failure
  if [ "$1" = hold ] && ! held; then printf '%s auto: %s\n' "$(date +%FT%T%z)" "${2:-security-relevant stage failed}" > "$RUN/HOLD"; fi
  if held; then signal_unsettled hold; wait_settled 30; fi
  stop_app; stop_db
  if held; then rec db_remove HELD; rec secrets_removed HELD
    say "HELD: resources.txt items and $PRIV are kept; in-flight DB/HTTP effects are UNKNOWN; disposal needs a separate decision"
  elif removal_allowed; then
    remove_db
    if [ "$DB_ALL_ABSENT" != yes ]; then rec secrets_removed "KEPT (DB not confirmed absent; recovery material)"
    elif [ ! -e "$PRIV" ] && [ ! -L "$PRIV" ] && [ -r "$RUN" ] && [ -x "$RUN" ]; then
      # V5b: reached only after removal_allowed (no HOLD/interruption, ops settled, app stopped) and remove_db confirmed
      # every recorded DB resource ABSENT by successful listings NOW, and private is absent NOW in a readable RUN.
      # The history line only explains why it is absent (removed earlier); it never overrides a current UNKNOWN.
      if grep -q $'^secrets_removed\tPASS\t' "$RUN/status.tsv"; then rec secrets_removed PASS; else rec secrets_removed NOT_CREATED; fi
    else secret_rm secrets_removed "$PRIV" > /dev/null; [ -e "$PRIV" ] || rec secrets_removed PASS; fi
  else rec db_remove NOT_RUN; rec secrets_removed NOT_RUN; say "PENDING: see $RUN/status.tsv, ops/ and resources.txt; nothing removed by guess"; fi
}

# ---- G4: one verdict, recomputed from current state (never from an earlier hold-status file) ----------------
verdict() {
  if [ "$REC_FAILED" = yes ]; then say "RESULT UNKNOWN (a status record write failed): $RUN"; return 2; fi
  local a r x settled=yes dbs; ops_all_settled || settled=no
  a=$(last app_stop); r=$(last db_remove); x=$(last secrets_removed)
  if held; then
    dbs=$(db_state)
    if [ "$settled" = yes ] && [[ "$dbs" =~ ^(NOT_CREATED|ABSENT|STOPPED)$ ]]; then
      printf 'confirmed=yes ops=settled db=%s %s\n' "$dbs" "$(date +%FT%T%z)" >> "$RUN/hold-status"
      say "RESULT HELD (stop confirmed; nothing removed): $RUN"; return 4; fi
    printf 'confirmed=no ops=%s db=%s %s\n' "$settled" "$dbs" "$(date +%FT%T%z)" >> "$RUN/hold-status"
    say "RESULT HOLD REQUESTED, stop NOT CONFIRMED (UNKNOWN): $RUN"; return 2
  fi
  if [ "$(last preflight)" != PASS ] && [ ! -e "$PRIV" ] && [ ! -f "$RUN/db-up-attempted" ] && [ "$settled" = yes ]; then
    say "RESULT STOPPED before secrets/services: $RUN"; return 3; fi
  if [ -n "$(last interrupted)" ] || [ "$settled" = no ] || ! [[ "$a" =~ ^(yes|not-started)$ ]] ||
     ! [[ "$r" =~ ^(PASS|NOT_CREATED)$ ]] || ! [[ "$x" =~ ^(PASS|NOT_CREATED)$ ]]; then
    say "RESULT PENDING/UNKNOWN: $RUN"; return 2; fi
  if [ "$(last demo)" = rc=0 ] && [ "$a" = yes ] && [ "$r" = PASS ] && [ "$x" = PASS ]; then say "RESULT PASS: $RUN"; return 0; fi
  say "RESULT FAIL (created resources confirmed removed): $RUN"; return 1
}
end_main() { local v; finalize "$1" "${2:-}"; verdict; v=$?; close_fixed_fd; exit "$v"; }
on_signal() { rec interrupted "state of in-flight operations is UNKNOWN"; signal_unsettled interrupt; end_main close; }
fail_stage() {   # V5: every unexpected failure after preflight goes to containment (hold); nothing is removed automatically
  rec "$1" "$2"
  if held; then end_main hold; elif [ "$1" = preflight ]; then end_main close; else end_main hold "stage $1 failed"; fi; }
env_guard() {    # V5: refuse overrides that would change Docker/Maven/Java behaviour; names only, values never read
  local k
  for k in DOCKER_HOST DOCKER_CONTEXT DOCKER_CERT_PATH DOCKER_TLS_VERIFY DOCKER_CONFIG \
           MAVEN_OPTS MAVEN_ARGS MAVEN_CONFIG JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
    [ -n "${!k+x}" ] && refuse "environment override present: $k (value not read)"; done
  [ -e "$HOME/.mavenrc" ] && refuse "~/.mavenrc exists (not read)"
  [ -e /etc/mavenrc ] && refuse "/etc/mavenrc exists (not read)"
  [ -e "$REPO/.mvn" ] && refuse "repo .mvn exists"
  return 0; }
secret_check() {   # V5b: NAME SECRET_FILE...; exact exclusions: the secret input dir and this tool's output dir only
  local n=$1 a=() f; shift
  for f in "$@"; do a+=(--secret "$f"); done
  timeout 60 python3 -I -B "$REPO/scripts/task-evidence-demo-secret-check.py" "${a[@]}" --root "$RUN" \
    --exclude "$PRIV" --exclude "$RUN/checks" > "$RUN/checks/$n.json"; }
check_hold() {   # V5b: a failed status write also stops the main line (containment, no removal)
  [ "$REC_FAILED" = yes ] && end_main hold "status record write failed"
  held && { rec hold_seen_by_main "no further dispatch"; end_main hold; }; return 0; }
after_dispatch() { [ "$DRC" = held ] && check_hold; [ "$DRC" = control ] && fail_stage "$1" "UNKNOWN (not started)"; check_hold; }

run() {
  locate_repo || exit 64
  check_source_gate || exit 64
  env_guard
  validate_root
  mkdir -p -- "$ROOT" && mode_ok "$ROOT" || refuse "cannot create a private RUNROOT"
  RUN_ID=$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n'); [[ "$RUN_ID" =~ ^[0-9a-f]{16}$ ]] || refuse "no run id"
  take_fixed_lock run 0 || refuse "fixed demo resources are in use (a process still holds $LOCKFILE): not taken over"
  RUN="$ROOT/run-$(date +%Y%m%dT%H%M%S)-$RUN_ID"; mkdir -- "$RUN" || refuse "run directory exists"
  PRIV="$RUN/private"; printf '%s\n' "$RUN_ID" > "$RUN/run-id.txt"
  printf 'app_baseline_commit=%s\nrun_head=%s\n' "$APP_BASELINE_COMMIT" "$RUN_HEAD" > "$RUN/run-source.txt" || refuse "cannot record run source"
  local mst; mst=$(starttime $$); valid_start "$mst" || refuse "own process start time unreadable"
  printf '%s %s\n' "$$" "$mst" > "$RUN/main.pid"; open_ctl; set_compose
  ulimit -c 0
  trap on_signal INT TERM HUP
  say "run directory: $RUN"
  preflight "$RUN"; local p=$?; [ "$p" = 0 ] && rec preflight PASS || fail_stage preflight "STOP rc=$p"
  check_hold
  mkdir -m 700 -- "$RUN/checks" || fail_stage checks_dir "FAIL"
  timeout 30 java -version > "$RUN/java-version.txt" 2>&1 || fail_stage toolchain "java -version failed"
  timeout 30 mvn -v > "$RUN/mvn-version.txt" 2>&1 || fail_stage toolchain "mvn -v failed"
  rec toolchain PASS; check_hold
  dispatch normal secrets_dir inherit 10 "$RUN/ops.log" mkdir -m 700 -- "$PRIV"; after_dispatch secrets_dir
  [ "$DRC" = 0 ] && mode_ok "$PRIV" || fail_stage secrets "FAIL (private dir)" hold
  dispatch normal secrets_create inherit 20 "$RUN/ops.log" python3 -I -B -c "$MAKE_SECRETS_PY" "$PRIV"; after_dispatch secrets_create
  [ "$DRC" = 0 ] && rec secrets PASS || fail_stage secrets "FAIL rc=$DRC" hold
  SECRET_ENV_FILE="$PRIV/bootstrap_password"; DISPATCH_MARK="$RUN/db-up-attempted"
  dispatch normal db_up may-close 120 "$RUN/db-up.log" "${COMPOSE_BASE[@]}" up -d --pull never postgres; local urc=$DRC
  SECRET_ENV_FILE=; DISPATCH_MARK=
  record_resources; local rrc=$?          # before any hold handling, so containment knows the exact IDs
  after_dispatch db_up
  [ "$urc" = 0 ] && [ "$rrc" = 0 ] && rec db_up PASS || fail_stage db_up "FAIL up_rc=$urc resources_rc=$rrc"
  local deadline=$((SECONDS+60)) h hrc   # V5: accepted intermediate state "starting" only; a failed query stops
  while :; do check_hold
    h=$(dk 10 inspect -f '{{.State.Health.Status}}' "$CID"); hrc=$?
    [ "$hrc" = 0 ] || fail_stage db_health "FAIL inspect rc=$hrc"
    case "$h" in healthy) break ;; starting) ;; *) fail_stage db_health "FAIL state not starting/healthy" ;; esac
    [ $SECONDS -lt $deadline ] || fail_stage db_health "FAIL timeout"; sleep 3; done; rec db_health PASS
  secret_rm rm_bootstrap_password "$PRIV/bootstrap_password"; local rm1rc=$?; after_dispatch rm_bootstrap_password
  [ "$rm1rc" = 0 ] || fail_stage rm_bootstrap_password "FAIL (file not confirmed deleted)"
  dispatch normal provision inherit 60 "$RUN/provision.out" env "DEMO_REPO=$REPO" "$REPO/scripts/task-evidence-demo-initdb.sh" \
    "$PRIV/app_password" "$PRIV/bootstrap_pgpass"; after_dispatch provision
  [ "$DRC" = 0 ] && rec provision PASS || fail_stage provision "FAIL rc=$DRC (see provision.out; DB transaction state UNKNOWN)" hold
  secret_check secret-check-1 "$PRIV/app_password" "$PRIV/bootstrap_pgpass" && rec secret_check_1 PASS || fail_stage secret_check_1 "FAIL (hit or tool error)"
  secret_rm rm_bootstrap_pgpass "$PRIV/bootstrap_pgpass"; local rm2rc=$?; after_dispatch rm_bootstrap_pgpass
  [ "$rm2rc" = 0 ] || fail_stage rm_bootstrap_pgpass "FAIL (file not confirmed deleted)"
  identity_check; local irc=$?; after_dispatch identity
  [ "$irc" = 0 ] && rec identity PASS || fail_stage identity "FAIL rc=$(cat "$RUN/identity-rc.txt")" hold
  dispatch normal build may-close 180 "$RUN/build.log" mvn -o -B -q -DskipTests package; after_dispatch build
  [ "$DRC" = 0 ] && rec build PASS || fail_stage build "FAIL rc=$DRC (offline build; not retried)"
  sha256sum "$REPO/target/java-task-manager-portfolio-0.0.1-SNAPSHOT.jar" > "$RUN/build-artifact.sha256" || fail_stage build "artifact hash"
  start_app; local arc=$?; check_hold
  [ "$arc" = 0 ] && rec app_ready yes || fail_stage app_ready "no rc=$arc"
  dispatch normal demo may-close 120 "$RUN/demo-console.log" python3 -I -B "$REPO/scripts/task-evidence-http-demo.py" run --origin "$ORIGIN" \
    --commit "$RUN_HEAD" --env-id "compose:$PROJECT run:$RUN_ID db:task_evidence_demo" --out "$RUN/demo"; local rc=$DRC
  echo "$rc" > "$RUN/demo-rc.txt"; rec demo "rc=$rc"; after_dispatch demo
  # V5: no automatic HTTP cleanup branch; a failed demo is contained (hold) for review
  [ "$rc" = 0 ] || fail_stage demo "FAIL rc=$rc (held; cleanup branch not run automatically)"
  secret_check secret-check-2 "$PRIV/app_password" && rec secret_check_2 PASS || fail_stage secret_check_2 "FAIL (hit or tool error)"
  end_main close
}

open_recorded_run() {   # stop/hold: only a recorded run-* directly under the run root
  locate_repo || exit 64; validate_root
  [ -d "$ROOT" ] || refuse "no run root"
  RUN=$(realpath -e -- "${1:?RUN directory}") || refuse "RUN not found"
  [ "$(dirname -- "$RUN")" = "$ROOT" ] && [[ "$(basename -- "$RUN")" =~ ^run-[0-9T]+-[0-9a-f]{16}$ ]] \
    || refuse "RUN must be a run-* directory directly under $ROOT"
  RUN_ID=$(cat "$RUN/run-id.txt" 2>/dev/null); [[ "$RUN" == *"-$RUN_ID" ]] || refuse "run id record does not match"
  [ -f "$RUN/status.tsv" ] && [ -f "$RUN/main.pid" ] || refuse "not a recorded run"
  PRIV="$RUN/private"; set_compose; open_ctl
}

stop_run() {   # close-out; never while the main process lives; never concurrently with another lock holder
  open_recorded_run "$1"
  local ms; ms=$(main_status)
  [ "$ms" = ENDED ] || refuse "the run's main process is $ms (not confirmed ended); wait, or use hold for containment"
  held && say "HOLD present: stop performs no removal"
  if ! take_fixed_lock stop 0; then
    signal_unsettled stop              # only this run's recorded, still-alive operation/app roots (pid+start verified)
    take_fixed_lock stop 30 || { say "fixed lock still held (possibly by an unrecorded descendant): nothing operated"; verdict; exit 2; }
  fi
  trap on_signal INT TERM HUP
  end_main close
}

hold_run() {   # request first (recorded), then stop verified processes; DB stop only while holding the fixed lock
  open_recorded_run "$1"
  ctl_lock || { say "HOLD REQUEST NOT RECORDED: control lock not obtained"; exit 2; }
  [ -f "$RUN/HOLD" ] || printf '%s requested-by-pid=%s\n' "$(date +%FT%T%z)" "$$" > "$RUN/HOLD"
  ctl_unlock
  rec hold_request RECORDED
  signal_unsettled hold
  local i ms; for i in $(seq 120); do ms=$(main_status); [ "$ms" = RUNNING ] || break; sleep 1; done
  ms=$(main_status)
  if [ "$ms" != ENDED ]; then say "main process is $ms after 120 s: DB not operated"; verdict; exit 2; fi
  if take_fixed_lock hold 30; then FINALIZED=no; finalize hold "requested by hold"
  else say "fixed lock still held (possibly by an unrecorded descendant): DB not operated"; fi
  verdict; local v=$?; close_fixed_fd; exit "$v"
}

main() {
  case "${1:-}" in
    preflight) locate_repo || exit 64; check_source_gate || exit 64; preflight ""; local r=$?
               say "preflight rc=$r (no files written, no secrets, no services)"; exit "$r";;
    run) [ $# = 1 ] || refuse "usage: run"; run;;
    stop) [ $# = 2 ] || refuse "usage: stop <RUN>"; stop_run "$2";;
    hold) [ $# = 2 ] || refuse "usage: hold <RUN>"; hold_run "$2";;
    *) sed -n '2,13p' "${BASH_SOURCE[0]}"; exit 64;;
  esac
}

main "$@"
