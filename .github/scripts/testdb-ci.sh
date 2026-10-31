#!/usr/bin/env bash
# Job-local PostgreSQL for the guarded CI test mode. Subcommands: setup | verify | cleanup.
#
# Fixed CI policy (built into TestDatabasePolicyProvider, never derived from the datasource or DB responses):
#   jdbc:postgresql://localhost:5432/task_manager_test, database task_manager_test,
#   login/effective/owner role jtm_test, TEST_DB_MODE=ci, no TEST_DB_POLICY_FILE.
#
# Secrets: two random values generated inside the job (admin, application). They live only in files under a 0700
# directory, reach psql through stdin and Maven through its environment; never argv, -D, GITHUB_OUTPUT or tracing.
# Admin authority is used only in `setup`, through the container's local socket. The image's local socket trust means
# anyone able to run `docker exec` on this runner can act as superuser; the admin password does not prevent that.
set -euo pipefail
set +x

readonly ROLE=jtm_test
readonly DATABASE=task_manager_test
readonly HOST_BIND=127.0.0.1:5432
# Expected provisioning flags, fixed here: canlogin|super|createdb|createrole|replication|bypassrls|memberships|owner
readonly EXPECTED_FLAGS="t|f|f|f|f|f|0|${ROLE}"

die() { echo "TESTDB_CI_ERROR: $1" >&2; exit 1; }

work_dir() {
    [ -n "${RUNNER_TEMP:-}" ] && [ -d "${RUNNER_TEMP}" ] && [ ! -L "${RUNNER_TEMP}" ] || die "RUNNER_TEMP_INVALID"
    printf '%s/jtm-ci' "${RUNNER_TEMP}"
}

require_name() {
    [ -n "${JTM_PG_NAME:-}" ] && [[ "${JTM_PG_NAME}" =~ ^jtm-ci-pg-[A-Za-z0-9_.-]+$ ]] || die "CONTAINER_NAME_INVALID"
    [ -n "${JTM_PG_IMAGE:-}" ] && [[ "${JTM_PG_IMAGE}" == postgres:17.11-alpine@sha256:* ]] || die "IMAGE_INVALID"
}

new_secret() {
    # 32 random bytes as 64 lowercase hex characters; written by the generator itself, never passed as an argument.
    python3 -I -c 'import secrets,sys; sys.stdout.write(secrets.token_hex(32))' > "$1" || die "SECRET_GENERATION_FAILED"
    [[ "$(cat "$1")" =~ ^[0-9a-f]{64}$ ]] || die "SECRET_INVALID"
}

setup() {
    require_name
    local w; w="$(work_dir)"
    umask 077
    [ ! -e "${w}" ] || die "WORK_DIR_EXISTS"
    mkdir -m 700 "${w}" "${w}/secrets" "${w}/out" || die "WORK_DIR_CREATE_FAILED"

    new_secret "${w}/secrets/admin"
    new_secret "${w}/secrets/app"
    # Masking is an additional layer only; it does not prove absence of leakage.
    echo "::add-mask::$(cat "${w}/secrets/admin")"
    echo "::add-mask::$(cat "${w}/secrets/app")"

    # Created, not started: no host directory is mounted. The admin secret is copied into the container below as a
    # root-owned 0400 file inside a root-owned 0700 directory; the host file stays 0600. The image entrypoint reads
    # POSTGRES_PASSWORD_FILE in docker_setup_env while still root (before `exec gosu postgres`), then unsets it.
    local cid
    cid="$(docker create --name "${JTM_PG_NAME}" -p "${HOST_BIND}:5432" \
        -e POSTGRES_PASSWORD_FILE=/run/jtm-secret/admin \
        "${JTM_PG_IMAGE}")" || die "CONTAINER_CREATE_FAILED"
    [[ "${cid}" =~ ^[0-9a-f]{64}$ ]] || die "CONTAINER_ID_INVALID"
    printf '%s\n' "${cid}" > "${w}/container.id" || die "CONTAINER_ID_RECORD_FAILED"
    echo "TESTDB_CI_CONTAINER_ID=${cid}"

    # The archive (secret included) is streamed from the generator to `docker cp` through a pipe; never argv or disk.
    # Without -a, docker cp creates the files as root:root inside the container; the archive also says 0:0.
    python3 -I - "${w}" <<'PY' | docker cp - "${cid}:/run" || die "ADMIN_SECRET_COPY_FAILED"
import io, sys, tarfile
w = sys.argv[1]
uid = gid = 0
secret = open(w + "/secrets/admin", "rb").read()
with tarfile.open(fileobj=sys.stdout.buffer, mode="w|") as out:
    directory = tarfile.TarInfo("jtm-secret")
    directory.type, directory.mode, directory.uid, directory.gid = tarfile.DIRTYPE, 0o700, uid, gid
    out.addfile(directory)
    item = tarfile.TarInfo("jtm-secret/admin")
    item.size, item.mode, item.uid, item.gid = len(secret), 0o400, uid, gid
    out.addfile(item, io.BytesIO(secret))
PY
    docker start "${cid}" > /dev/null || die "CONTAINER_START_FAILED"

    local mapping
    mapping="$(docker port "${cid}" 5432/tcp)" || die "PORT_QUERY_FAILED"
    [ "${mapping}" = "${HOST_BIND}" ] || die "PORT_MAPPING_UNEXPECTED"

    local ready=0 i
    for i in $(seq 1 60); do
        if docker exec "${cid}" pg_isready -q -h 127.0.0.1 -U postgres -d postgres; then ready=1; break; fi
        sleep 1
    done
    [ "${ready}" = 1 ] || die "READINESS_TIMEOUT"

    # Provisioning SQL travels on stdin. The application secret is checked to be 64 hex characters above, so the
    # single-quoted psql variable needs no further escaping. Terse verbosity and no context keep statement text out
    # of client error output; client output is captured, never displayed.
    local app
    app="$(cat "${w}/secrets/app")"
    if ! printf '%s\n' \
        '\set ON_ERROR_STOP 1' '\set VERBOSITY terse' '\set SHOW_CONTEXT never' '\set ECHO none' \
        "\\set pw '${app}'" \
        "CREATE ROLE ${ROLE} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD :'pw';" \
        '\unset pw' \
        "CREATE DATABASE ${DATABASE} OWNER ${ROLE};" \
        'REVOKE ALL ON DATABASE postgres FROM PUBLIC;' \
        | docker exec -i "${cid}" psql -X -q -U postgres -d postgres > "${w}/out/provision.log" 2>&1; then
        unset app
        die "PROVISION_FAILED (details withheld)"
    fi
    unset app

    local flags
    flags="$(printf '%s\n' \
        "SELECT r.rolcanlogin, r.rolsuper, r.rolcreatedb, r.rolcreaterole, r.rolreplication, r.rolbypassrls,
                (SELECT count(*) FROM pg_auth_members m WHERE m.member = r.oid),
                (SELECT o.rolname FROM pg_database d JOIN pg_roles o ON o.oid = d.datdba WHERE d.datname = '${DATABASE}')
           FROM pg_roles r WHERE r.rolname = '${ROLE}';" \
        | docker exec -i "${cid}" psql -X -q -A -t -F '|' -v ON_ERROR_STOP=1 -U postgres -d postgres)" \
        || die "PROVISION_CHECK_FAILED"
    [ "${flags}" = "${EXPECTED_FLAGS}" ] || die "PROVISION_FLAGS_MISMATCH ${flags}"
    echo "TESTDB_CI_PROVISION_FLAGS=${flags} (canlogin|super|createdb|createrole|replication|bypassrls|memberships|owner)"
    touch "${w}/provisioned" || die "MARKER_FAILED"
}

verify() {
    local w; w="$(work_dir)"
    [ -f "${w}/provisioned" ] || die "NOT_PROVISIONED"
    [ "${TEST_DB_MODE:-}" = ci ] && [ -z "${TEST_DB_POLICY_FILE+x}" ] || die "MODE_ENV_UNEXPECTED"
    local rc=0
    # Output is captured in the private directory and shown only by the scanning step after a clean scan.
    # The secret file has no trailing newline, so `read` returns non-zero at EOF after filling the variable.
    ( IFS= read -r DB_PASSWORD < "${w}/secrets/app" || [ -n "${DB_PASSWORD:-}" ] || exit 97; export DB_PASSWORD
      exec mvn --batch-mode --no-transfer-progress clean verify ) > "${w}/out/maven.log" 2>&1 || rc=$?
    printf '%s\n' "${rc}" > "${w}/out/maven.rc" || die "RC_RECORD_FAILED"
    echo "MAVEN_RC=${rc} (output withheld until the secret scan passes)"
    return "${rc}"
}

cleanup() {
    local w status=0; w="$(work_dir)"
    require_name
    local cid=""
    if [ -f "${w}/container.id" ]; then
        cid="$(cat "${w}/container.id")"
    else
        # docker run may have created the container before its ID was recorded.
        cid="$(docker ps -aq --no-trunc --filter "name=^/${JTM_PG_NAME}$")" || status=1
    fi
    if [ -n "${cid}" ]; then
        if [[ "${cid}" =~ ^[0-9a-f]{64}$ ]] && [ "$(docker inspect --format '{{.Name}}' "${cid}")" = "/${JTM_PG_NAME}" ]; then
            docker rm -f -v "${cid}" > /dev/null || { echo "TESTDB_CI_ERROR: CONTAINER_REMOVE_FAILED" >&2; status=1; }
        else
            echo "TESTDB_CI_ERROR: CONTAINER_IDENTITY_MISMATCH" >&2; status=1
        fi
    fi
    if [ -e "${w}" ]; then
        if [ -d "${w}" ] && [ ! -L "${w}" ]; then
            rm -rf -- "${w}" || { echo "TESTDB_CI_ERROR: WORK_DIR_REMOVE_FAILED" >&2; status=1; }
        else
            echo "TESTDB_CI_ERROR: WORK_DIR_UNEXPECTED" >&2; status=1
        fi
    fi
    echo "TESTDB_CI_CLEANUP_STATUS=${status}"
    return "${status}"
}

case "${1:-}" in
    setup) setup ;;
    verify) verify ;;
    cleanup) cleanup ;;
    *) die "USAGE" ;;
esac
