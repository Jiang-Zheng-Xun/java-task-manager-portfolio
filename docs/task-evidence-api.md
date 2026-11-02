# Task Evidence API

Evidence records are append-only notes attached to an existing Task. Creating Evidence never changes Task fields and does not mean the Task is accepted or done.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/tasks/{id}/evidence` | Create one Evidence record for Task `{id}` |
| `GET` | `/api/tasks/{id}/evidence` | List Evidence for Task `{id}`, newest first |

## POST /api/tasks/{id}/evidence

### Request

The body is a JSON object with exactly one required string field, `content`.

```json
{ "content": "Reviewed the API contract with synthetic data" }
```

### Validation order

1. JSON structure is read by a dedicated converter, used only for the exact request DTO:
   - an unknown key, a duplicate key (after decoding), a structural error, or trailing tokens returns `400` `"Malformed JSON request"`;
   - a missing, `null`, or non-string `content` returns `400` `"content must be a string"`.
2. Content rules, applied in this order:
   - NUL and unpaired surrogates are rejected;
   - leading and trailing characters are trimmed when `Character.isWhitespace` or `Character.isSpaceChar` matches;
   - the trimmed value must be non-blank and at most 2000 code points;
   - internal formatting is kept, and no Unicode normalization is applied.
3. Input validation finishes before any repository call or Task-existence check.

### Responses

- `201 Created`, with exactly four fields: `id`, `taskId`, `content`, `createdAt`. There is no `Location` header.
- `createdAt` is UTC and truncated to microseconds.
- Repeating the same POST creates a new record with a different `id`.
- `404` when the Task does not exist.
- An unexpected lock or integrity error returns a safe `500`. The failed transaction is not re-queried, and the request is not retried automatically.

### Transaction behaviour

The create runs in a real transaction:
- it sets a transaction-local `lock_timeout` of 5 seconds;
- it locks the parent Task with `FOR KEY SHARE`;
- it inserts and flushes, then commits.

### Converter boundary

The dedicated converter uses `mapper.copy()` of the shared mapper and its own `ObjectReader`. It reads only the exact request DTO. It does not change the shared mapper, and it does not take over other DTOs or response writing.

## GET /api/tasks/{id}/evidence

- Query parameters: `page` (default `0`) and `size` (default `20`, allowed `1`–`100`).
- `404` when the Task does not exist. The existence check runs before any large-offset handling.
- An empty page, or a page past the end, returns `200` with `[]`.
- Ordering is `createdAt DESC`, then `id DESC`.
- The body is a JSON array, and the `X-Has-Next-Page` header gives `true` or `false`. There is no total count.
- The read is `readOnly`. It promises neither REPEATABLE READ nor a snapshot across pages.
- If the Task is deleted after the existence check, the response can be `200 []` with `X-Has-Next-Page: false`. Later GETs return `404`.

## Schema (migration V2)

`task_evidence` table:
- identity primary key;
- `task_id` foreign key to `tasks` with `ON DELETE CASCADE`;
- `content VARCHAR(2000) NOT NULL`;
- `created_at TIMESTAMPTZ NOT NULL`, with no default;
- `CHECK (BTRIM(content) <> '')`. With no second argument, `BTRIM` removes only ordinary spaces (U+0020) at both ends, so this check rejects content made only of U+0020 spaces. It does not reject every ASCII whitespace character, such as tabs or newlines;
- a composite index on task, creation time and id.

The database CHECK is intentionally weaker than the Java Unicode rules. The Java layer is the full validation boundary.

## Usage example

Prerequisites: the application is running locally, and a Task with id `1` already exists. These commands are illustrative and use synthetic data.

```bash
curl -i -X POST http://localhost:8080/api/tasks/1/evidence \
  -H 'Content-Type: application/json' \
  -d '{"content":"Synthetic evidence note"}'

curl -i 'http://localhost:8080/api/tasks/1/evidence?page=0&size=20'
```

See [task-evidence-verification.md](task-evidence-verification.md) for how this behaviour was verified and the limits of that evidence.
