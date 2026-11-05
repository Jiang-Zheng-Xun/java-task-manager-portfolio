#!/usr/bin/env python3
"""Task Evidence demo — local secret comparison (Demo-only thin tool, V5b).
Usage: task-evidence-demo-secret-check.py --secret FILE [--secret FILE]... --root DIR [--root DIR]... [--exclude PATH]...
- Secret files: a pgpass line contributes only its last field. Values are never printed.
- Scans every regular file under each ROOT. Only the EXACT --exclude paths (secret input dir, this tool's output dir) are
  skipped. Any missing/unreadable root, walk error, unreadable file, symlink or special file -> ERROR (no silent gaps).
- Prints ONE JSON line of counts / fixed categories only.
Exit 0 = clean (no secret value, no SCRAM verifier format); 1 = hit; 3 = ERROR (coverage not complete)."""
import json, os, re, stat, sys

def out(d, code):
    print(json.dumps(d, sort_keys=True)); sys.exit(code)

secrets, roots, excl = [], [], []
a = sys.argv[1:]
i = 0
while i < len(a):
    if a[i] in ("--secret", "--root", "--exclude") and i + 1 < len(a):
        {"--secret": secrets, "--root": roots, "--exclude": excl}[a[i]].append(a[i + 1]); i += 2
    else:
        out({"result": "ERROR", "category": "usage"}, 3)
if not secrets or not roots:
    out({"result": "ERROR", "category": "usage"}, 3)
excl = {os.path.realpath(p) for p in excl}
vals = []
for p in secrets:
    try:
        data = open(p, "rb").read().strip()
    except OSError:
        out({"result": "ERROR", "category": "secret_unreadable"}, 3)
    if data.count(b":") >= 4:
        data = data.splitlines()[0].rsplit(b":", 1)[1]
    if len(data) < 16:
        out({"result": "ERROR", "category": "secret_too_short"}, 3)
    vals.append(data)
verifier = re.compile(rb"SCRAM-SHA-256\$[0-9]+:")
errors = []
def onerr(e): errors.append(type(e).__name__)
files = hits = vhits = excluded = 0
for root in roots:
    if not os.path.isdir(root) or os.path.islink(root) or not os.access(root, os.R_OK | os.X_OK):
        out({"result": "ERROR", "category": "root_missing_or_unreadable"}, 3)
    for dp, dns, fns in os.walk(root, onerror=onerr):
        keep = []
        for d in dns:
            p = os.path.join(dp, d)
            if os.path.realpath(p) in excl and not os.path.islink(p):
                excluded += 1; continue
            if os.path.islink(p):
                out({"result": "ERROR", "category": "symlink_dir_present"}, 3)
            keep.append(d)
        dns[:] = keep
        for fn in fns:
            p = os.path.join(dp, fn)
            try:
                s = os.lstat(p)
            except OSError:
                out({"result": "ERROR", "category": "lstat_failed"}, 3)
            if not stat.S_ISREG(s.st_mode):
                out({"result": "ERROR", "category": "symlink_or_special_file_present"}, 3)
            try:
                b = open(p, "rb").read()
            except OSError:
                out({"result": "ERROR", "category": "file_unreadable"}, 3)
            files += 1
            hits += any(v in b for v in vals)
            vhits += bool(verifier.search(b))
if errors:
    out({"result": "ERROR", "category": "walk_error", "count": len(errors)}, 3)
res = {"result": "CLEAN" if hits == 0 and vhits == 0 else "HIT", "secrets_checked": len(vals), "files_scanned": files,
       "files_with_secret_value": hits, "files_with_verifier_format": vhits, "excluded_dirs": excluded}
out(res, 0 if res["result"] == "CLEAN" else 1)
