#!/usr/bin/env python3
"""Task Evidence demo: thin libpq boundary for SCRAM provisioning (D1 r5). Demo-only; not product code.

  python3 -I -B scripts/task-evidence-demo-libpq.py preflight
  python3 -I -B scripts/task-evidence-demo-libpq.py provision --app-password-file <600 file> --bootstrap-passfile <600 pgpass>

preflight  loads libpq from the fixed system path only, checks PQlibVersion and the required symbols, confirms
           the mapped file in /proc/self/maps, and prints one de-secreted JSON line. It reads no secret.
provision  repeats preflight, then (and only then) reads the application password into a ctypes buffer,
           connects over TCP to the fixed demo target as the bootstrap role (password from a pgpass file read by
           libpq itself), asks libpq PQencryptPasswordConn(..., "scram-sha-256") for the verifier, creates the
           restricted owner, transfers ownership and disables the bootstrap password, in one transaction.

Output is one JSON line {"result", "stage", "category", ...versions}. It never contains a password, a verifier,
SQL text, a connection string, PQerrorMessage text, environment values or exception text.
Exit codes: 0 OK, 3 compatibility/precondition stop, 4 provisioning failure (state may need inspection).

Connection environment (r5): before anything else, main() removes every inherited PG* variable from this
process (names are listed in the output, values are never read), so PGPASSWORD, PGSERVICE, PGSERVICEFILE,
PGSYSCONFDIR, PGOPTIONS etc. cannot supply credentials or settings. The connection also fixes gssencmode=disable
and require_auth=scram-sha-256 (no other authentication method is accepted by libpq).
Secret files (r5): the directory is opened and checked with fstat, the file is opened relative to it with
O_NOFOLLOW and checked with fstat (regular, owner, 0600-or-stricter, one link). libpq receives the bootstrap
pgpass as /proc/self/fd/<n> of that verified open file, not by path.

Nothing happens at import time: the library is loaded only inside main(). No retries, no fallback algorithm,
no fallback library path, no MD5, no plaintext PASSWORD, no self-made SCRAM computation.

Limits (see runbook §6): Python and libpq cannot guarantee that every copy of a secret is erased. Buffers
owned here are overwritten before release, but the kernel socket buffers, libpq/OpenSSL internals, allocator
free lists, swap, core dumps and any process with ptrace/root access are outside this script's control.
"""
import ctypes, json, os, stat, sys

# ---- fixed policy ------------------------------------------------------------------------------------------
LIBPQ_PATH = "/usr/lib/x86_64-linux-gnu/libpq.so.5"   # absolute system path only; never repo, cwd or user input
LIBPQ_TRUSTED_DIR = "/usr/lib/x86_64-linux-gnu"
LIBPQ_MIN_VERSION = 170000   # demo policy: same major as the demo server image (17.x) or newer. Functional SCRAM
                             # floor is 100000 (libpq 10); that floor is NOT a security-maintenance target.
REQUIRED_SYMBOLS = ("PQlibVersion", "PQconnectdbParams", "PQstatus", "PQfinish", "PQserverVersion",
                    "PQsetNoticeProcessor", "PQencryptPasswordConn", "PQescapeLiteral", "PQfreemem",
                    "PQexec", "PQresultStatus", "PQclear")
REPO_DIR = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
ISO_ROOT = "/home/logos/jtm-evidence-demo-20261102"   # evidence-* directories under it never hold secrets
LOADER_ENV_BLOCKLIST = ("LD_PRELOAD", "LD_LIBRARY_PATH", "LD_AUDIT", "PYTHONPATH", "PYTHONHOME")
TARGET = (("host", "127.0.0.1"), ("port", "55433"), ("dbname", "task_evidence_demo"),
          ("user", "task_evidence_demo_bootstrap"), ("sslmode", "disable"), ("gssencmode", "disable"),
          ("require_auth", "scram-sha-256"), ("connect_timeout", "5"),
          ("application_name", "jtm-evidence-demo-provision"), ("options", "-c statement_timeout=10s"))
APP_ROLE = b"task_evidence_demo_owner"
ALGORITHM = b"scram-sha-256"          # explicit; never NULL (NULL would defer to the server's password_encryption)
VERIFIER_PREFIX = b"SCRAM-SHA-256$"
MAX_SECRET = 1024

CONNECTION_OK = 0          # ConnStatusType (libpq-fe.h)
PGRES_COMMAND_OK = 1       # ExecStatusType (libpq-fe.h)


class Stop(Exception):
    """De-secreted stop: carries only a stage and a fixed category."""
    def __init__(self, stage, category, code=3):
        super().__init__(category); self.stage, self.category, self.code = stage, category, code


def emit(result, stage, category, info, code):
    print(json.dumps({"result": result, "stage": stage, "category": category, **info}, sort_keys=True))
    sys.exit(code)


# ---- stage: compatibility (no secrets) ---------------------------------------------------------------------
def check_environment(info):
    if not (sys.flags.isolated and sys.flags.dont_write_bytecode):
        raise Stop("python", "python_must_run_with_-I_-B")
    if sys.version_info < (3, 8):
        raise Stop("python", "python_too_old")
    info["python"] = "%d.%d.%d" % sys.version_info[:3]
    info["python_executable"] = os.path.realpath(sys.executable)
    present = [k for k in LOADER_ENV_BLOCKLIST if k in os.environ]   # names only; values are never read
    if present:
        info["loader_env_present"] = present
        raise Stop("loader", "loader_environment_override_present")


def check_library_file(info):
    real = os.path.realpath(LIBPQ_PATH)
    info["libpq_path"], info["libpq_realpath"] = LIBPQ_PATH, real
    if os.path.dirname(real) != LIBPQ_TRUSTED_DIR:
        raise Stop("loader", "libpq_outside_trusted_dir")
    for p in (LIBPQ_PATH, real, LIBPQ_TRUSTED_DIR):
        try:
            st = os.stat(p)
        except OSError:
            raise Stop("loader", "libpq_missing")
        if st.st_uid != 0 or st.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
            raise Stop("loader", "libpq_not_root_owned_or_writable")
    if not stat.S_ISREG(os.stat(real).st_mode):
        raise Stop("loader", "libpq_not_regular_file")
    return real


def mapped_libpq_files():
    found = set()
    with open("/proc/self/maps", encoding="ascii", errors="replace") as f:
        for line in f:
            parts = line.split(None, 5)
            if len(parts) == 6 and "/libpq.so" in parts[5]:
                found.add(parts[5].strip())
    return found


def load_libpq(info):
    real = check_library_file(info)
    before = mapped_libpq_files()
    if before:
        raise Stop("loader", "libpq_already_mapped_before_load")
    try:
        lib = ctypes.CDLL(LIBPQ_PATH, mode=os.RTLD_NOW | os.RTLD_LOCAL)
    except OSError:
        raise Stop("loader", "libpq_load_failed")
    mapped = mapped_libpq_files()
    info["libpq_mapped"] = sorted(mapped)
    if mapped != {real}:
        raise Stop("loader", "libpq_mapped_file_unexpected")
    missing = [s for s in REQUIRED_SYMBOLS if not hasattr(lib, s)]
    if missing:
        info["missing_symbols"] = missing
        raise Stop("symbols", "required_symbol_missing")
    declare(lib)
    v = lib.PQlibVersion()
    info["libpq_version"] = v
    if v < LIBPQ_MIN_VERSION:
        raise Stop("version", "libpq_below_policy_minimum")
    return lib


NoticeProcessor = ctypes.CFUNCTYPE(None, ctypes.c_void_p, ctypes.c_char_p)   # void (*)(void *arg, const char *msg)


def declare(lib):
    """Every native function used, with explicit argument and return types (libpq-fe.h, PostgreSQL 17 docs §32.11/§32.3)."""
    P, S, I, Z = ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_size_t
    lib.PQlibVersion.argtypes, lib.PQlibVersion.restype = [], I                       # int PQlibVersion(void)
    lib.PQconnectdbParams.argtypes = [ctypes.POINTER(S), ctypes.POINTER(S), I]        # PGconn *(const char *const *keywords,
    lib.PQconnectdbParams.restype = P                                                 #   const char *const *values, int expand_dbname)
    lib.PQstatus.argtypes, lib.PQstatus.restype = [P], I                              # ConnStatusType PQstatus(const PGconn *)
    lib.PQfinish.argtypes, lib.PQfinish.restype = [P], None                           # void PQfinish(PGconn *)
    lib.PQserverVersion.argtypes, lib.PQserverVersion.restype = [P], I                # int PQserverVersion(const PGconn *)
    lib.PQsetNoticeProcessor.argtypes = [P, NoticeProcessor, P]                       # PQnoticeProcessor PQsetNoticeProcessor(
    lib.PQsetNoticeProcessor.restype = P                                              #   PGconn *, PQnoticeProcessor, void *)
    lib.PQencryptPasswordConn.argtypes = [P, P, S, S]                                 # char *(PGconn *, const char *passwd,
    lib.PQencryptPasswordConn.restype = P                                             #   const char *user, const char *algorithm); raw pointer kept
    lib.PQescapeLiteral.argtypes, lib.PQescapeLiteral.restype = [P, P, Z], P          # char *PQescapeLiteral(PGconn *, const char *, size_t)
    lib.PQfreemem.argtypes, lib.PQfreemem.restype = [P], None                         # void PQfreemem(void *)
    lib.PQexec.argtypes, lib.PQexec.restype = [P, P], P                               # PGresult *PQexec(PGconn *, const char *)
    lib.PQresultStatus.argtypes, lib.PQresultStatus.restype = [P], I                  # ExecStatusType PQresultStatus(const PGresult *)
    lib.PQclear.argtypes, lib.PQclear.restype = [P], None                             # void PQclear(PGresult *)


# ---- secret boundary ---------------------------------------------------------------------------------------
def clear_pg_environment(info):
    """Remove inherited libpq settings/credentials from this process before any libpq use. Values are not read."""
    names = sorted(k for k in list(os.environ) if k.startswith("PG"))
    for k in names:
        del os.environ[k]                 # os.environ deletion calls unsetenv(), so libpq's getenv() no longer sees it
    info["pg_env_removed"] = names


def open_private(path, stage):
    """Open a secret file and verify the opened objects (fstat), not only the path. Returns an fd."""
    d, name = os.path.split(path)
    if not os.path.isabs(path) or not name:
        raise Stop(stage, "secret_path_not_absolute")
    real_d = os.path.realpath(d)
    if (real_d == REPO_DIR or real_d.startswith(REPO_DIR + os.sep)
            or real_d.startswith(os.path.join(ISO_ROOT, "evidence-"))):
        raise Stop(stage, "secret_location_forbidden")
    try:
        dfd = os.open(real_d, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    except OSError:
        raise Stop(stage, "secret_dir_unavailable")
    try:
        dst = os.fstat(dfd)
        if dst.st_uid != os.getuid() or dst.st_mode & 0o077:
            raise Stop(stage, "secret_dir_not_private")
        try:
            fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=dfd)
        except OSError:
            raise Stop(stage, "secret_file_unavailable")
    finally:
        os.close(dfd)
    st = os.fstat(fd)
    if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid() or st.st_mode & 0o077 or st.st_nlink != 1:
        os.close(fd)
        raise Stop(stage, "secret_file_not_private_regular_file")
    return fd


def read_secret(path):
    """Read into a ctypes buffer (no Python bytes/str copy of the value). Caller must wipe()."""
    fd = open_private(path, "secret_read")
    buf = ctypes.create_string_buffer(MAX_SECRET + 2)
    try:
        n = os.readv(fd, [memoryview(buf)[:MAX_SECRET + 1]])
    finally:
        os.close(fd)
    while n and buf[n - 1] in (b"\n", b"\r"):
        n -= 1
    if n == 0 or n > MAX_SECRET:
        wipe(buf); raise Stop("secret_read", "secret_length_invalid")
    ctypes.memset(ctypes.addressof(buf) + n, 0, len(buf) - n)
    return buf, n


def wipe(buf):
    if buf is not None:
        ctypes.memset(ctypes.addressof(buf), 0, ctypes.sizeof(buf))


def c_strlen(ptr, limit):
    n = 0
    while n <= limit and ctypes.c_char.from_address(ptr + n).value != b"\0":
        n += 1
    return n


def free_native(lib, ptr, wipe_len=0):
    if ptr:
        if wipe_len:
            ctypes.memset(ptr, 0, wipe_len)
        lib.PQfreemem(ptr)


def connect(lib, passfile):
    pfd = open_private(passfile, "connect")          # verified open file; libpq reads it through /proc/self/fd
    try:
        params = TARGET + (("passfile", "/proc/self/fd/%d" % pfd),)
        keys = (ctypes.c_char_p * (len(params) + 1))(*[k.encode() for k, _ in params], None)
        vals = (ctypes.c_char_p * (len(params) + 1))(*[v.encode() for _, v in params], None)
        conn = lib.PQconnectdbParams(keys, vals, 0)   # synchronous: the passfile is read before this returns
    finally:
        os.close(pfd)
    if not conn:                         # NULL only when libpq could not allocate the PGconn
        raise Stop("connect", "pgconn_allocation_failed", 4)
    return conn


def exec_ok(lib, conn, sql_ptr, stage):
    res = lib.PQexec(conn, sql_ptr)
    try:
        if not res or lib.PQresultStatus(res) != PGRES_COMMAND_OK:
            raise Stop(stage, "statement_failed", 4)     # PQresultErrorMessage is deliberately not read
    finally:
        if res:
            lib.PQclear(res)


def exec_const(lib, conn, sql, stage):
    exec_ok(lib, conn, ctypes.c_char_p(sql), stage)


def provision(lib, info, app_pw_file, passfile):
    pw = None; conn = None; verifier = None; vlen = 0; lit = None; llen = 0; stmt = None
    keep_notice = NoticeProcessor(lambda arg, msg: None)   # discard notices: they are never printed
    try:
        pw, pwlen = read_secret(app_pw_file)                                   # stage: secret_read
        conn = connect(lib, passfile)                                          # stage: connect
        lib.PQsetNoticeProcessor(conn, keep_notice, None)
        if lib.PQstatus(conn) != CONNECTION_OK:
            raise Stop("connect", "connection_not_ok", 4)                      # PQerrorMessage deliberately not read
        info["server_version"] = lib.PQserverVersion(conn)
        exec_const(lib, conn, b"SET log_statement = 'none'", "session_logging")
        exec_const(lib, conn, b"SET log_min_error_statement = 'panic'", "session_logging")
        exec_const(lib, conn, b"SET log_min_duration_statement = -1", "session_logging")
        exec_const(lib, conn, b"SET log_min_duration_sample = -1", "session_logging")
        verifier = lib.PQencryptPasswordConn(conn, ctypes.cast(pw, ctypes.c_void_p), APP_ROLE, ALGORITHM)
        wipe(pw); pw = None                                                    # stage: verifier
        if not verifier:
            raise Stop("verifier", "pqencryptpasswordconn_failed", 4)
        vlen = c_strlen(verifier, MAX_SECRET)
        if vlen > MAX_SECRET:
            vlen = 0; raise Stop("verifier", "verifier_length_unexpected", 4)
        if vlen < len(VERIFIER_PREFIX) or ctypes.string_at(verifier, len(VERIFIER_PREFIX)) != VERIFIER_PREFIX:
            raise Stop("verifier", "verifier_format_unexpected", 4)
        lit = lib.PQescapeLiteral(conn, verifier, vlen)
        if not lit:
            raise Stop("verifier", "escape_failed", 4)
        llen = c_strlen(lit, 2 * MAX_SECRET + 8)
        if llen > 2 * MAX_SECRET + 8:
            llen = 0; raise Stop("verifier", "escaped_length_unexpected", 4)
        free_native(lib, verifier, vlen); verifier = None
        head = (b"CREATE ROLE " + APP_ROLE + b" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION "
                b"NOBYPASSRLS PASSWORD ")   # same attributes as r3; checked by runbook identity step
        stmt = ctypes.create_string_buffer(len(head) + llen + 1)
        ctypes.memmove(stmt, head, len(head)); ctypes.memmove(ctypes.addressof(stmt) + len(head), lit, llen)
        free_native(lib, lit, llen); lit = None
        exec_const(lib, conn, b"BEGIN", "role_setup")
        exec_ok(lib, conn, stmt, "role_setup")
        wipe(stmt); stmt = None
        exec_const(lib, conn, b"ALTER DATABASE task_evidence_demo OWNER TO task_evidence_demo_owner", "role_setup")
        exec_const(lib, conn, b"ALTER SCHEMA public OWNER TO task_evidence_demo_owner", "role_setup")
        exec_const(lib, conn, b"ALTER ROLE task_evidence_demo_bootstrap PASSWORD NULL", "role_setup")
        exec_const(lib, conn, b"COMMIT", "role_setup")
    finally:
        wipe(pw); wipe(stmt)
        free_native(lib, verifier, vlen); free_native(lib, lit, llen)
        if conn:
            lib.PQfinish(conn)          # an open transaction is rolled back by the server on disconnect


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("preflight", "provision"):
        sys.stderr.write("usage: preflight | provision --app-password-file F --bootstrap-passfile F\n"); sys.exit(64)
    mode, info, stage = sys.argv[1], {"mode": sys.argv[1], "libpq_min_version": LIBPQ_MIN_VERSION}, "start"
    try:
        stage = "environment"; clear_pg_environment(info); check_environment(info)
        stage = "load"; lib = load_libpq(info)
        if mode == "provision":
            a = sys.argv[2:]
            if len(a) != 4 or a[0] != "--app-password-file" or a[2] != "--bootstrap-passfile":
                raise Stop("arguments", "provision_arguments_invalid")
            stage = "provision"; provision(lib, info, a[1], a[3])
    except Stop as e:
        emit("STOP", e.stage, e.category, info, e.code)
    except KeyboardInterrupt:
        emit("INTERRUPTED", stage, "interrupted_state_unknown", info, 4)
    except Exception as e:                        # type name only; never the message or traceback
        emit("ERROR", stage, "unexpected_" + type(e).__name__, info, 4)
    emit("OK", mode, "none", info, 0)


if __name__ == "__main__":
    main()
