# Complete local API demo

This demo exercises a running localhost server through curl. It complements MockMvc/PostgreSQL tests; it does not establish deployment readiness or replace transaction and database assertions.

Prerequisites: the repository's Java/Maven/Docker setup, Bash, curl and Python 3. Use two terminals. Do not run regression tests concurrently with this demo. Existing Tasks may remain in the database.

## 1. Start the application

In terminal A, from the repository root:

```bash
set -a
source .env
set +a
docker compose ps
mvn spring-boot:run
```

Confirm PostgreSQL is healthy and startup reports `Started JavaTaskManagerPortfolioApplication`. Keep this terminal running. Defaults bind the application to 127.0.0.1:8080.

## 2. Prepare the demo terminal

In terminal B, start Bash and enter the repository:

```bash
bash
cd /home/logos/projects/java-task-manager-portfolio
```

Use the actual clone path if different. Run the following setup in this same Bash session:

```bash
demo_base=http://127.0.0.1:8080
demo_dir=$(mktemp -d)
demo_id=

demo_request() {
  local method=$1 path=$2 expected=$3
  shift 3
  local actual
  actual=$(curl --silent --show-error --max-time 15 \
    --dump-header "$demo_dir/headers" \
    --output "$demo_dir/body" \
    --write-out '%{http_code}' \
    --request "$method" \
    --header 'Accept: application/json' \
    "$@" "$demo_base$path") || return 1
  printf '%s %s -> %s (expected %s)\n' \
    "$method" "$path" "$actual" "$expected"
  cat "$demo_dir/headers"
  cat "$demo_dir/body"
  printf '\n'
  test "$actual" = "$expected"
}
```

If any command or assertion fails, stop the normal sequence and use the cleanup instructions below. HTTP status mismatches are failures even when curl itself exits successfully.

## 3. Create and read

```bash
demo_request POST /api/tasks 201 \
  --header 'Content-Type: application/json' \
  --data '{"title":"Day 12 demo task","description":"Demo-owned record"}'
```

After 201 succeeds, capture the generated ID and validate Location:

```bash
demo_id=$(python3 - "$demo_dir/body" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    body = json.load(f)
assert type(body["id"]) is int and body["id"] > 0
assert body["status"] == "TODO"
print(body["id"])
PY
)
printf 'Demo-owned Task ID: %s\n' "$demo_id"

python3 - "$demo_dir/headers" "$demo_id" <<'PY'
import sys
with open(sys.argv[1]) as f:
    headers = f.read().splitlines()
locations = [line.split(":", 1)[1].strip()
             for line in headers
             if line.lower().startswith("location:")]
assert locations == ["/api/tasks/" + sys.argv[2]], locations
print("Location matches generated ID")
PY

demo_request GET "/api/tasks/$demo_id" 200
```

Confirm title, description and TODO in the GET response. IDs and timestamps vary; never substitute an unrelated existing ID.

## 4. List a bounded page

```bash
demo_request GET '/api/tasks?page=0&size=2' 200
```

Expect a JSON array and `X-Has-Next-Page: true` or `false`. The values depend on existing data. The demo Task need not be on the first page; this request alone does not prove multi-page ordering or all pagination boundaries.

## 5. Complete and reopen

```bash
demo_request PATCH "/api/tasks/$demo_id/status" 200 \
  --header 'Content-Type: application/json' \
  --data '{"status":"COMPLETED"}'
demo_request GET "/api/tasks/$demo_id" 200

demo_request PATCH "/api/tasks/$demo_id/status" 200 \
  --header 'Content-Type: application/json' \
  --data '{"status":"IN_PROGRESS"}'
demo_request GET "/api/tasks/$demo_id" 200
```

Confirm COMPLETED, then IN_PROGRESS, with the original ID, title and description retained. The automated lifecycle and committed-state tests provide the more precise field and timestamp assertions.

## 6. Replace all editable fields

```bash
demo_request PUT "/api/tasks/$demo_id" 200 \
  --header 'Content-Type: application/json' \
  --data '{"title":"Day 12 demo replaced","description":null,"status":"COMPLETED"}'
demo_request GET "/api/tasks/$demo_id" 200
```

Confirm the new title, null description and COMPLETED status.
PUT requires description to be present even when its value is null.

## 7. Delete and verify absence

```bash
demo_request DELETE "/api/tasks/$demo_id" 204
test ! -s "$demo_dir/body"
demo_request GET "/api/tasks/$demo_id" 404
demo_request DELETE "/api/tasks/$demo_id" 404
```

Expect an empty DELETE 204 body and safe JSON 404 responses.
Repeated DELETE leaves the resource absent despite the different status.

## 8. Cleanup and shutdown

After successful completion, the demo-owned row is already absent.

If interrupted after capturing the ID, remove only that row:

```bash
if [[ "$demo_id" =~ ^[1-9][0-9]*$ ]]; then
  curl --silent --show-error --max-time 15 --include \
    --request DELETE \
    --header 'Accept: application/json' \
    "$demo_base/api/tasks/$demo_id"
fi
```

Expect 204 if it remained, or 404 if already deleted. If creation succeeded but ID capture failed, inspect the saved create response and recover its actual ID before cleanup. Do not delete by title, clear the table, or substitute another ID. A cleanup network error does not prove deletion; resolve it and verify GET 404 before removing the temporary response files.

Remove temporary files after confirming absence:

```bash
rm -r -- "$demo_dir"
unset demo_id demo_dir demo_base
unset -f demo_request
```

Stop the application in terminal A with Ctrl+C. Keep PostgreSQL running if further verification is planned. At final project shutdown use `docker compose down` without `-v` to retain the volume.

## Evidence to record

Record the actual generated ID, each expected/actual HTTP status,
Location match, observed field changes, list header, empty 204 body, post-delete and repeated-delete 404, and cleanup result.

This demo does not require an empty database or demonstrate every validation, security, pagination or concurrent-access scenario. Consult the automated tests and architecture document for those evidence boundaries.
