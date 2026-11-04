#!/usr/bin/env python3
"""Task Evidence HTTP demo (operation spec v1.0). Python standard library + the system curl binary.

  python3 -I -B scripts/task-evidence-http-demo.py run     --origin http://127.0.0.1:18080 --commit <sha> --env-id <id> --out <new dir>
  python3 -I -B scripts/task-evidence-http-demo.py cleanup --origin http://127.0.0.1:18080 --run <existing run dir>

run:     normal sequence; stops at the first unexpected result; never retries a request.
cleanup: separate branch; deletes only the Task ID saved by that run, for the same origin; writes to a new unique
         cleanup-<time> directory created before any request.
Each request uses curl with: no config file (-q), no proxy, no redirects, --connect-timeout 3 and --max-time 15
(total per request), plus a process-level backstop. Results: steps.jsonl, NN-<step>.json and, for handled outcomes,
result.json (not guaranteed after SIGKILL, power loss or disk failure). Exit: 0 PASS, 1 FAIL/ERROR,
2 UNKNOWN/UNRESOLVED/INTERRUPTED.
"""
import argparse, json, os, subprocess, sys, tempfile, time
from datetime import datetime
from urllib.parse import urlsplit

ALLOWED_ORIGIN = "http://127.0.0.1:18080"
CONNECT_S, TOTAL_S = 3, 15


class Fail(Exception):
    pass


class Unknown(Exception):
    """transport error / timeout: the server-side effect is unknown."""


class Unresolved(Exception):
    """cleanup cannot determine or reach the Task (e.g. it may exist but its ID is unknown)."""


def check_origin(origin):
    u = urlsplit(origin)
    if (origin != ALLOWED_ORIGIN or u.scheme != "http" or u.username or u.password or u.hostname != "127.0.0.1"
            or u.port != 18080 or u.path not in ("",) or u.query or u.fragment):
        sys.exit(f"origin must be exactly {ALLOWED_ORIGIN}")


def now():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


class Client:
    def __init__(self, origin, out):
        self.origin = origin; self.out = out; self.n = 0
        self.log = open(os.path.join(out, "steps.jsonl"), "x", encoding="utf-8")
        self.env = {k: v for k, v in os.environ.items() if k.lower() not in ("http_proxy", "https_proxy", "all_proxy", "no_proxy", "curl_home")}

    def note(self, **rec):
        self.log.write(json.dumps({"t": now(), **rec}, ensure_ascii=False) + "\n"); self.log.flush()

    def req(self, name, method, path, body=None):
        self.n += 1
        with tempfile.TemporaryDirectory(dir=self.out) as td:
            hdr, out = os.path.join(td, "h"), os.path.join(td, "b")
            argv = ["curl", "-q", "--silent", "--show-error", "--noproxy", "*", "--proto", "=http", "--max-redirs", "0",
                    "--connect-timeout", str(CONNECT_S), "--max-time", str(TOTAL_S), "-X", method,
                    "-H", "Accept: application/json", "-D", hdr, "-o", out, "-w", "%{http_code}"]
            if body is not None:
                bf = os.path.join(td, "req"); open(bf, "wb").write(body)
                argv += ["-H", "Content-Type: application/json", "--data-binary", "@" + bf]
            argv.append(self.origin + path)
            t0 = now()
            try:
                p = subprocess.run(argv, capture_output=True, text=True, env=self.env, timeout=TOTAL_S + 5)
                rc, code, err = p.returncode, p.stdout.strip(), p.stderr.strip()
            except subprocess.TimeoutExpired:
                rc, code, err = "backstop-timeout", "000", "process backstop expired"
            raw = open(out, "rb").read() if os.path.exists(out) else b""
            hraw = open(hdr, "rb").read().decode("latin1") if os.path.exists(hdr) else ""
        headers = {}
        for line in hraw.split("\r\n")[1:]:
            if ":" in line:
                k, v = line.split(":", 1); headers[k.strip().lower()] = v.strip()
        rec = {"step": self.n, "name": name, "method": method, "path": path, "started": t0, "finished": now(),
               "request_body": body.decode("utf-8") if body is not None else None, "curl_rc": rc, "curl_stderr": err,
               "status": int(code) if code.isdigit() else None, "headers": headers, "body": raw.decode("utf-8", "replace")}
        with open(os.path.join(self.out, f"{self.n:02d}-{name}.json"), "x", encoding="utf-8") as f:
            json.dump(rec, f, ensure_ascii=False, indent=1)
        if rc != 0 or rec["status"] in (None, 0):
            raise Unknown(f"{name}: transport failure rc={rc}")
        return rec["status"], headers, raw

    def check(self, name, ok, detail=""):
        self.note(assert_=name, ok=bool(ok), detail=str(detail)[:300])
        if not ok:
            raise Fail(f"{name}: {detail}")


def js(c, name, raw, kind):
    try:
        j = json.loads(raw)
    except ValueError:
        c.check(name + "_json", False, "body is not JSON")
    c.check(name + "_json_" + kind.__name__, isinstance(j, kind), type(j).__name__)
    return j


def evidence(c, tid, content):
    tag = content[-1]
    st, hd, raw = c.req("create-evidence-" + tag, "POST", f"/api/tasks/{tid}/evidence", json.dumps({"content": content}).encode())
    c.check(f"ev{tag}_201", st == 201, st)
    c.check(f"ev{tag}_no_location", "location" not in hd, hd.get("location"))
    j = js(c, f"ev{tag}", raw, dict)
    c.check(f"ev{tag}_exact_fields", set(j) == {"id", "taskId", "content", "createdAt"}, sorted(j))
    c.check(f"ev{tag}_taskId", j["taskId"] == tid, j["taskId"]); c.check(f"ev{tag}_content", j["content"] == content, j["content"])
    datetime.fromisoformat(j["createdAt"].replace("Z", "+00:00"))
    return j


def key(e):  # full microsecond precision, then id
    return (datetime.fromisoformat(e["createdAt"].replace("Z", "+00:00")), e["id"])


def run(c, state):
    with open(os.path.join(c.out, "create-attempted.txt"), "x") as f:  # written before the POST is sent
        f.write(now() + "\n")
    st, hd, raw = c.req("create-task", "POST", "/api/tasks",
                        json.dumps({"title": "Evidence demo synthetic task", "description": "Synthetic data for the Evidence HTTP demo"}).encode())
    c.check("task_201", st == 201, st)
    task = js(c, "task", raw, dict); tid = task.get("id")
    c.check("task_id_int", isinstance(tid, int) and not isinstance(tid, bool), tid)
    state["task_id"] = tid
    with open(os.path.join(c.out, "task-id.txt"), "x") as f:
        f.write(f"{tid}\n")
    st, hd, raw = c.req("get-task-before", "GET", f"/api/tasks/{tid}")
    c.check("task_before_200", st == 200, st); before = js(c, "task_before", raw, dict)
    a = evidence(c, tid, "Demo evidence A"); b = evidence(c, tid, "Demo evidence B")
    state["evidence_ids"] = [a["id"], b["id"]]
    c.check("ev_ids_differ", a["id"] != b["id"], state["evidence_ids"])
    expected = sorted([a, b], key=key, reverse=True)
    got = []
    for page, nxt in ((0, "true"), (1, "false")):
        st, hd, raw = c.req(f"list-page-{page}", "GET", f"/api/tasks/{tid}/evidence?page={page}&size=1")
        c.check(f"p{page}_200", st == 200, st); j = js(c, f"p{page}", raw, list)
        c.check(f"p{page}_one", len(j) == 1, len(j)); c.check(f"p{page}_has_next_{nxt}", hd.get("x-has-next-page") == nxt, hd.get("x-has-next-page"))
        got += j
    c.check("pages_equal_created_objects_in_order", got == expected, [e.get("id") for e in got])
    st, hd, raw = c.req("duplicate-key", "POST", f"/api/tasks/{tid}/evidence", b'{"content":"first","content":"second"}')
    c.check("dup_400", st == 400, st); c.check("dup_message", js(c, "dup", raw, dict).get("message") == "Malformed JSON request", raw[:200])
    st, hd, raw = c.req("list-size-20", "GET", f"/api/tasks/{tid}/evidence?page=0&size=20")
    c.check("s20_200", st == 200, st); j = js(c, "s20", raw, list)
    c.check("s20_still_two_same_objects", j == expected, len(j)); c.check("s20_has_next_false", hd.get("x-has-next-page") == "false", hd.get("x-has-next-page"))
    st, hd, raw = c.req("get-task-after", "GET", f"/api/tasks/{tid}")
    c.check("task_after_200", st == 200, st); c.check("task_json_unchanged", js(c, "task_after", raw, dict) == before, "structural comparison")
    st, hd, raw = c.req("delete-task", "DELETE", f"/api/tasks/{tid}")
    c.check("delete_204", st == 204, st); c.check("delete_empty_body", raw == b"", len(raw))
    state["deleted"] = True
    st, _, _ = c.req("get-task-after-delete", "GET", f"/api/tasks/{tid}"); c.check("task_404", st == 404, st)
    st, _, _ = c.req("get-evidence-after-delete", "GET", f"/api/tasks/{tid}/evidence"); c.check("evidence_404", st == 404, st)


def cleanup(c, state, run_dir, origin):
    meta = json.load(open(os.path.join(run_dir, "run.json")))
    if meta.get("origin") != origin:
        raise Fail("run origin differs from cleanup origin")
    idf = os.path.join(run_dir, "task-id.txt")
    if not os.path.exists(idf):
        if not os.path.exists(os.path.join(run_dir, "create-attempted.txt")):
            state["cleanup"] = "NOT_CREATED: the run never sent the create request"; return
        rec = os.path.join(run_dir, "01-create-task.json")
        r = json.load(open(rec)) if os.path.exists(rec) else {}
        if r.get("curl_rc") == 0 and isinstance(r.get("status"), int) and 400 <= r["status"] < 500:
            state["cleanup"] = f"NOT_CREATED: create request answered {r['status']}"; return
        raise Unresolved("create request may have created a Task but no ID was obtained; not guessing, not deleting by title")
    tid = int(open(idf).read().strip()); state["task_id"] = tid
    st, _, _ = c.req("probe-task", "GET", f"/api/tasks/{tid}")
    if st == 404:
        state["cleanup"] = "already absent (GET 404); no DELETE sent"; return
    c.check("probe_200", st == 200, st)
    st, _, raw = c.req("delete-task", "DELETE", f"/api/tasks/{tid}")  # sent once; never resent
    c.check("cleanup_delete_204", st == 204, st)
    st, _, _ = c.req("confirm-task-404", "GET", f"/api/tasks/{tid}"); c.check("cleanup_confirm_404", st == 404, st)
    state["cleanup"] = "deleted and confirmed 404"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=["run", "cleanup"]); p.add_argument("--origin", required=True)
    p.add_argument("--out"); p.add_argument("--run"); p.add_argument("--commit"); p.add_argument("--env-id")
    a = p.parse_args(); check_origin(a.origin)
    # all preconditions before any request
    if a.mode == "run":
        if not (a.out and a.commit and len(a.commit) == 40 and a.env_id):
            sys.exit("run needs --out (new), --commit (40 hex) and --env-id")
        os.mkdir(a.out, 0o700)  # fails if it exists: no re-entry into an old run directory
        out = a.out
        json.dump({"origin": a.origin, "commit": a.commit, "env_id": a.env_id, "created": now()}, open(os.path.join(out, "run.json"), "x"))
    else:
        if not (a.run and os.path.isfile(os.path.join(a.run, "run.json"))):
            sys.exit("cleanup needs --run pointing to an existing run directory")
        out = os.path.join(a.run, "cleanup-" + time.strftime("%Y%m%dT%H%M%S"))
        os.mkdir(out, 0o700)  # unique, created before any request
    c = Client(a.origin, out); state = {"mode": a.mode, "started": now()}; result = "PASS"
    try:
        run(c, state) if a.mode == "run" else cleanup(c, state, a.run, a.origin)
    except Fail as e:
        result = "FAIL: " + str(e)
    except Unknown as e:
        result = "UNKNOWN (server effect not known; do not assume success, do not resend): " + str(e)
    except Unresolved as e:
        result = "UNRESOLVED: " + str(e)
    except KeyboardInterrupt:
        result = "INTERRUPTED (state of the in-flight request is UNKNOWN)"
    except Exception as e:  # unexpected structure or local error
        result = "ERROR: " + repr(e)[:300]
    state.update(result=result, finished=now())
    # best effort: written on handled outcomes; SIGKILL, power loss or disk errors can still leave no result.json
    json.dump(state, open(os.path.join(out, "result.json"), "x"), indent=1)
    print(result); sys.exit(0 if result == "PASS" else 2 if result.startswith(("UNRESOLVED", "UNKNOWN", "INTERRUPTED")) else 1)


if __name__ == "__main__":
    main()
