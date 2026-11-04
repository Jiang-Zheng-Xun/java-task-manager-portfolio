# Task Evidence HTTP demo runbook

## 怎麼讀這份文件

| 你現在要做什麼 | 讀哪裡 |
|---|---|
| 操作 Demo | **A. 正常操作**：從 A1 依序做到 A8，全程只用一個 WSL 終端 |
| 看到某個現象，不確定正不正常 | **B. 這樣正常嗎？** |
| `run` 還在跑，但必須立刻阻斷 | **C. 異常區**（正常成功者跳過） |
| 想逐項看 12 個請求與 41 項斷言 | **D. 附錄** |
| 查 timeout、底層原理、歷史證據 | **參考區**（§1 起），操作時不用讀 |

全程三條底線：

1. **首個非預期結果就停止。** 不重試、不自行修正、不重跑，把看到的內容回報。每一步的「不符合時」會告訴你要回報什麼。
2. **不碰秘密。** 不讀 `private/` 內容，不對 demo container 執行 `docker inspect`，不印環境變數或 `/proc/<pid>/environ`，不用 `bash -x`，不上傳未審查的原始日誌。
3. **`run` 進行中不按 Ctrl-C、不關終端。**

文件與畫面不一致時，不需要自己讀程式判斷，停止並回報差異即可。

命令區塊請整塊複製貼上。區塊內刻意不放 `#` 註解，因為互動式 zsh 預設不把 `#` 當註解。A6、A7 的區塊會逐步檢查：任何一步失敗就印出 `STOP：…`，同一區塊後面的步驟不會再執行，你的終端也不會被關掉。

## A. 正常操作

### A1. 這個 Demo 要證明什麼

**一句話：** 在隔離環境啟動真的資料庫與 app，用 HTTP 走完一段任務情境，留下可以查的紀錄，然後把建立過的東西收乾淨。

**情境：** 建立一個合成任務，附上兩筆成果證據（Evidence），查詢它們的分頁與排序，嘗試一筆重複 key 的錯誤輸入，確認任務內容沒有被新增 Evidence 改動，最後刪除任務並檢查後續查詢。

**你只會用到兩個命令：** `preflight` 是事前檢查，不產生秘密、不啟動服務、不寫檔。`run` 從啟動到收尾一次做完。

```text
A2 確認位置 → A3 preflight → A4 run，等它結束 → A5 設定 RUN → A6 讀成果 → A7 查收尾 → A8 回饋
```

正常情況下，只有 A8 需要把結果交出去。A2～A7 符合預期就直接往下做，不必每一行都回 Chat 確認。

### A2. 確認環境、位置與來源狀態

**目的：** 確認你在對的目錄，而且要操作的是已提交的乾淨版本。

**先理解兩個版本：**

- **固定應用基線** `6ce1ca5d277fa864c33e75ccfecfdafb3790741d`：已驗證過的應用來源，也就是 `pom.xml` 與 `src/main/`。它固定不變，不是這次執行的版本。
- **當次 HEAD**：你這次實際操作的 commit。之後提交文件或 Demo 工具，HEAD 會往前走，這是正常的。`run` 會把兩個值都記在 `RUN/run-source.txt`，HTTP Demo 紀錄的是當次 HEAD。

`preflight` 與 `run` 開始前會檢查：基線是當次 HEAD 的祖先，`pom.xml` 與 `src/main/` 的提交內容和實際檔案都與基線相同，工作區沒有未提交或未追蹤的檔案。受忽略的 `target/` 不影響。完整的拒絕清單見 §3.13。

**工作區條件：** 只在乾淨、已提交的工作區依本文操作。有未提交或未追蹤的檔案時，`preflight` 與 `run` 會以 `exit=64` 拒絕，這是預期結果。不要為了通過而 `git add`、`commit` 或修改入口。

**位置：** 開一個 WSL 的 Linux 終端，不要用 PowerShell 或 cmd。接下來 A2～A7 都在這個終端。zsh 或 bash 都可以直接貼。入口的鎖與隔離位置固定在 `/home/logos/jtm-evidence-demo-20261102`，不保證在其他機器或其他 clone 路徑可以直接執行。

**命令：**

```bash
cd /home/logos/jtm-evidence-demo-20261102/repo && git rev-parse --show-toplevel && git rev-parse HEAD && git merge-base --is-ancestor 6ce1ca5d277fa864c33e75ccfecfdafb3790741d HEAD && git status --short && echo "RUNROOT=[${RUNROOT:-}]"
```

**預期輸出：** 剛好三行。`git status --short` 在乾淨時沒有輸出，所以中間沒有其他行。

```text
/home/logos/jtm-evidence-demo-20261102/repo
<當次 HEAD，40 位十六進位>
RUNROOT=[]
```

**通過：** 第一行相同、HEAD 與 `RUNROOT=[]` 之間沒有其他行，就記下當次 HEAD，往 A3。

**不符合時：** 停止，回報畫面。沒有出現 `RUNROOT=[]` 代表前面某一步失敗，例如基線不是當次 HEAD 的祖先。不要 commit、切換 branch 或修改任何檔案。

### A3. preflight：開始前的檢查

**目的：** 在產生任何秘密或服務之前，先確認工具、資料庫函式庫、端口都就緒，沒有上一次留下的資源。

**位置：** 同一個終端。

**命令：**

```bash
scripts/task-evidence-demo.sh preflight; rc=$?; echo "exit=$rc"
if [ "$rc" = 0 ]; then docker image ls postgres:17.11-alpine; echo "image_rc=$?"; fi
```

**預期輸出：** 每行前面有 `[HH:MM:SS]`。

```text
[HH:MM:SS] python libpq preflight rc=0 {…"result": "OK"…}
[HH:MM:SS] psql: …; psql libpq: …; pgJDBC (pom): 42.7.12
[HH:MM:SS] preflight rc=0 (no files written, no secrets, no services)
exit=0
REPOSITORY   TAG            IMAGE ID   CREATED   SIZE
postgres     17.11-alpine   …          …         …
image_rc=0
```

**通過：** `exit=0`，映像除了標題列還有一列，`image_rc=0`，就往 A4。第二項是因為 `preflight` 不查本機映像，要先看一眼。

**不符合時：** 停止，回報 `exit` 的值與該行訊息。`source gate refused: …` 加 `exit=64` 表示來源或工作區不符合 A2 的條件。此時還沒有秘密或服務，不要為了通過而安裝、升級、改設定或提交。

### A4. 一次正常 run，等它結束

**目的：** 跑完整個情境：啟動資料庫、建置、啟動 app、送出 HTTP 請求、收尾。

**位置：** 同一個終端。不需要開第二個終端，執行中也不需要做任何事。

**命令：**

```bash
scripts/task-evidence-demo.sh run; echo "exit=$?"
```

**等待：** 貼上後等到出現 `exit=` 和提示字元。中途某些階段（特別是 `build`）會有一段時間沒有新行，這是正常的（見 B）。一次已完成的紀錄約 31 秒，這只是參考，不是保證。

執行期間不要修改或提交專案。版本檢查只在開始時做一次，不能防止之後的改動。

**預期輸出：** 開頭與結尾長這樣，中間的完整階段清單在 §3.3。

```text
[HH:MM:SS] run directory: /home/…/.local/state/jtm-evidence-demo/run-<時間>-<16 位十六進位>
…
[HH:MM:SS] preflight: PASS
…
[HH:MM:SS] demo: rc=0
…
[HH:MM:SS] secrets_removed: PASS
[HH:MM:SS] RESULT PASS: /home/…/.local/state/jtm-evidence-demo/run-<時間>-<16 位十六進位>
exit=0
```

**通過：** 最後是 `RESULT PASS: …` 與 `exit=0`，就往 A5。收尾已自動完成，**不需要** `hold`，也**不需要**另外 `stop`。

**不符合時：**

- `run` 已結束但 `exit` 不是 0：停止，不要重跑，不要 `hold`，不要下其他命令，保留畫面。回報 `exit`、`RESULT` 行，以及第一個不是 PASS 的階段行。各 `exit` 值的意思見 C4。
- `run` 還沒結束，卻出現必須立刻阻斷的情況（例如畫面出現像秘密的文字）：到 C。
- `run` 超過該階段上限（§3.6，`build` 最長 180 秒）仍沒有新行：到 C。

### A5. 設定並確認本次 RUN

**目的：** 之後的命令要知道讀哪一個目錄，所以要把本次路徑存進終端變數 `RUN`。

**先理解三件事：**

- `RUN='…'` 只是在**目前這個終端**記下一段文字，它不執行任何東西，所以**成功時沒有任何輸出**。沒有輸出是正常的。
- 這個變數不會跨終端。開新終端或重開終端，`RUN` 就是空的。
- 路徑一律從**本次畫面**複製，不要用「最新的目錄」或時間猜。每次 run 的路徑都不同，文件裡不會有現成路徑可抄。

**位置：** 跑 `run` 的同一個終端。`run` 結束後才做。

**命令 1：** 複製 `RESULT PASS: ` 後面的完整路徑（`run directory:` 那一行是同一路徑），貼進單引號中間，取代中文字。

```bash
RUN='把這裡換成畫面上的完整路徑'
```

**預期輸出：** 無輸出。

**命令 2：** 確認設定成功。

```bash
if [ -n "${RUN:-}" ] && [ -f "$RUN/run-id.txt" ] && [ "${RUN##*-}" = "$(cat "$RUN/run-id.txt")" ]; then
  echo "RUN 確認通過：$RUN"
else
  echo "STOP：RUN 未設定、路徑不存在，或與 run-id.txt 不符"
fi
```

**預期輸出：** `RUN 確認通過：` 加上你剛貼的路徑。

**通過：** 印出的路徑和畫面 `RESULT` 行相同，就往 A6。

**不符合時：** 印出 `STOP：…` 就停止，回報這一行。這個檢查只證明路徑是一個完整的 run 目錄；「它是你這一次」靠的是你從本次畫面複製。

A6、A7 的命令區塊開頭都有同一段檢查。`RUN` 是空的或不對時，整塊只印 `STOP`，不會去讀 `/status.tsv` 這種錯誤路徑，也不會跑後面的命令。

### A6. 閱讀成果，理解案例

**目的：** `PASS` 只是起點。這一步確認本次成果，並讓你能說出這次做了什麼、證明了什麼。

**先看情境。** 一次 `run` 送出 12 個 HTTP 請求，對結果做 41 項斷言（assertion，也就是「這裡必須是這樣」的自動檢查）：

| 組 | 情境 | 目的 | 請求數 | 斷言數 |
|---|---|---|---:|---:|
| 1 | 建立合成任務 | 建立 Task，取得當次 ID，再讀一次留作比較基準 | 2 | 5 |
| 2 | 附上兩筆 Evidence | 新增 A、B，回應欄位與內容正確，兩筆 ID 不同 | 2 | 13 |
| 3 | 查分頁與排序 | 每頁 1 筆讀兩頁，翻頁標頭正確，順序與建立結果一致 | 2 | 9 |
| 4 | 重複 key 的錯誤輸入 | 回 400 與固定訊息，之後仍然只有兩筆 | 2 | 7 |
| 5 | 確認 Task 沒被改動 | 再讀 Task，JSON 與基準相同 | 1 | 3 |
| 6 | 刪除與後續查詢 | 刪除回 204 且無內容，之後 Task 與 Evidence 都回 404 | 3 | 4 |
| | **合計** | | **12** | **41** |

**41 不是 41 個操作步驟。** 你沒有操作 41 次，也不是送了 41 個請求。一個請求回來後會同時檢查好幾件事，所以斷言比請求多。以「新增 Evidence A」這一個請求為例，它有 6 項斷言：

| 檢查什麼 | 例子 | 白話 |
|---|---|---|
| HTTP 狀態 | `evA_201` | 伺服器回答 201，表示建立成功 |
| 標頭 | `evA_no_location` | 回應沒有 `Location` 標頭 |
| 格式 | `evA_json_dict` | 回應是 JSON 物件 |
| 欄位 | `evA_exact_fields` | 恰好有 `id`、`taskId`、`content`、`createdAt` 四個欄位，不多不少 |
| 內容 | `evA_taskId`、`evA_content` | 掛在這次的 Task 上，文字等於送出的 `Demo evidence A` |

分頁請求的標頭例子是 `p0_has_next_true`：第一頁的 `X-Has-Next-Page` 必須是 `true`。

**ID 是當次實際值。** `task_id`、`evidence_ids` 由資料庫分配，不一定是 1、2，每次也可能不同。斷言比對的是「和這次回應一致」，不是某個固定數字。

**位置：** 同一個終端。

**命令 1：** 看每個階段的結果與就緒紀錄。

```bash
if ! { [ -n "${RUN:-}" ] && [ -f "$RUN/run-id.txt" ] && [ "${RUN##*-}" = "$(cat "$RUN/run-id.txt")" ]; }; then
  echo "STOP：RUN 未設定、路徑不存在，或與 run-id.txt 不符"
elif ! cat "$RUN/status.tsv"; then
  echo "STOP：讀不到 status.tsv"
elif ! { echo "--- readiness"; cat "$RUN/readiness.tsv"; }; then
  echo "STOP：讀不到 readiness.tsv"
elif ! { echo "--- secret check 1"; cat "$RUN/checks/secret-check-1.json"; }; then
  echo "STOP：讀不到 secret-check-1.json"
elif ! { echo "--- secret check 2"; cat "$RUN/checks/secret-check-2.json"; }; then
  echo "STOP：讀不到 secret-check-2.json"
else
  echo "A6 命令 1 讀取完成"
fi
```

**預期輸出：** 最後一行是 `A6 命令 1 讀取完成`，前面的內容是：

- `status.tsv`：依序有 `preflight PASS`、`toolchain PASS`、`secrets PASS`、`db_up PASS`、`db_health PASS`、`rm_bootstrap_password DELETED rc=0`、`provision PASS`、`secret_check_1 PASS`、`rm_bootstrap_pgpass DELETED rc=0`、`identity PASS`、`build PASS`、`app_ready yes`、`demo rc=0`、`secret_check_2 PASS`、`app_stop yes`、`db_stop STOPPED rc=0`、三個 `remove_* rc=0 after=ABSENT`、`db_remove PASS`、`secrets_removed DELETED rc=0`、`secrets_removed PASS`。
- `readiness.tsv`：前面可能有幾行 `not_ready`，**最後一行**是 `404`、`RUNNING`、`ready`。這裡的 404 是好消息：app 故意查一個不存在的 ID，回 404 代表它已能回應。
- 兩份 secret check 都是 `"result": "CLEAN"`，內容只有計數。

**命令 2：** 看結果與計數。

```bash
if ! { [ -n "${RUN:-}" ] && [ -f "$RUN/run-id.txt" ] && [ "${RUN##*-}" = "$(cat "$RUN/run-id.txt")" ]; }; then
  echo "STOP：RUN 未設定、路徑不存在，或與 run-id.txt 不符"
elif python3 -I -B -c '
import json, os, re, sys

def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)

demo = os.path.join(sys.argv[1], "demo")
try:
    result = load(os.path.join(demo, "result.json"))
    with open(os.path.join(demo, "steps.jsonl"), encoding="utf-8") as f:
        steps = [json.loads(line) for line in f if line.strip()]
    files = [n for n in os.listdir(demo) if re.fullmatch("[0-9][0-9]-.*[.]json", n)]
    numbers = sorted(load(os.path.join(demo, n))["step"] for n in files)
    flags = [s["ok"] for s in steps]
    summary = (result["result"], result["deleted"], result["task_id"], result["evidence_ids"])
except (OSError, ValueError, KeyError, TypeError) as e:
    print("read or parse failed:", type(e).__name__)
    sys.exit(1)
passed = sum(v is True for v in flags)
failed = sum(v is False for v in flags)
print("result:", summary[0], "deleted:", summary[1])
print("task_id:", summary[2], "evidence_ids:", summary[3])
print("assertions_ok:", passed, "assertions_failed:", failed, "assertion_lines:", len(flags))
print("request_files:", len(files))
good = (summary[0] == "PASS" and summary[1] is True and passed == 41 and failed == 0
        and len(flags) == 41 and numbers == list(range(1, 13)))
sys.exit(0 if good else 1)
' "$RUN"; then
  echo "A6 計數確認通過"
else
  echo "STOP：A6 計數未通過，回報上面的數字或錯誤類型"
fi
```

**預期輸出：** ID 是當次的值，其他要相同。

```text
result: PASS deleted: True
task_id: <當次 ID> evidence_ids: [<當次 ID>, <當次 ID>]
assertions_ok: 41 assertions_failed: 0 assertion_lines: 41
request_files: 12
A6 計數確認通過
```

**怎麼讀：** 這段用 Python 標準函式庫真的解析 `result.json`、`steps.jsonl` 與 12 個請求檔，不是找文字。

| 畫面 | 意思 |
|---|---|
| `assertions_failed: 0` 加 `A6 計數確認通過` | 讀到檔案，也解析成功，失敗斷言確實是 0 |
| `read or parse failed: …` | 檔案不存在、不可讀或內容解析失敗。**這不是 0 筆**，也不會印出任何計數 |
| 有計數但印出 `STOP：A6 計數未通過` | 檔案讀到了，但結果或數字不符，或請求檔編號不是 1～12 |

**通過：** 上述都符合，而且你能用自己的話說出這次做了什麼、證明什麼，就往 A7。

**不符合時：** 停止，回報不同的那一行。不要自己重新計數或換一種命令再查。

**這些結果不能證明：**

- HTTP 404 不單獨證明 DB cascade 已發生，它只表示 API 查不到，不等於實體列被刪。
- 這不代表全面的 JSON、Unicode、交易或併發驗證。這裡只測了一種重複 key 輸入與一條循序路徑。
- secret check `CLEAN` 不保證沒有其他形式外洩。它只比對固定範圍、固定時點的實值與 verifier 格式（§3.3）。
- 一次正常成功不證明所有失敗路徑，也不證明環境可重現。

### A7. 查驗收尾

**目的：** `run` 收尾時說自己清乾淨了，這裡用獨立查詢再確認一次。這一步只查不改，不用另外清理，也不要刪 `RUN` 目錄。

**位置：** 同一個終端。

**命令：**

```bash
if ! { [ -n "${RUN:-}" ] && [ -f "$RUN/run-id.txt" ] && [ "${RUN##*-}" = "$(cat "$RUN/run-id.txt")" ]; }; then
  echo "STOP：RUN 未設定、路徑不存在，或與 run-id.txt 不符"
elif [ ! -r "$RUN" ] || [ ! -x "$RUN" ]; then
  echo "STOP：RUN 目錄無法讀取，無法可靠判斷 private"
elif [ -L "$RUN/private" ]; then
  echo "STOP：private 是 symlink（不跟隨、不讀取、不刪除）"
elif [ -e "$RUN/private" ]; then
  echo "STOP：private 仍存在（不讀取、不刪除）"
elif grep -E '^(interrupted|hold_request)|PARTIAL_OR_FAILED' "$RUN/status.tsv"; then
  echo "STOP：status.tsv 有中斷、hold 或刪除失敗紀錄（見上一行）"
elif [ "$?" -ne 1 ]; then
  echo "STOP：讀不到 status.tsv"
elif ! out=$(ss -ltnH 'sport = :55433'); then
  echo "STOP：55433 查詢失敗，狀態 UNKNOWN"
elif [ -n "$out" ]; then
  echo "STOP：55433 仍有 listener"
elif ! out=$(ss -ltnH 'sport = :18080'); then
  echo "STOP：18080 查詢失敗，狀態 UNKNOWN"
elif [ -n "$out" ]; then
  echo "STOP：18080 仍有 listener"
elif ! out=$(docker ps -a -q --filter label=com.docker.compose.project=jtm-evidence-demo); then
  echo "STOP：container 查詢失敗，狀態 UNKNOWN"
elif [ -n "$out" ]; then
  echo "STOP：本 project 仍有 container"
elif ! out=$(docker volume ls -q --filter label=com.docker.compose.project=jtm-evidence-demo); then
  echo "STOP：volume 查詢失敗，狀態 UNKNOWN"
elif [ -n "$out" ]; then
  echo "STOP：本 project 仍有 volume"
elif ! out=$(docker network ls -q --filter label=com.docker.compose.project=jtm-evidence-demo); then
  echo "STOP：network 查詢失敗，狀態 UNKNOWN"
elif [ -n "$out" ]; then
  echo "STOP：本 project 仍有 network"
else
  echo "A7 收尾確認通過：private 不存在，沒有異常紀錄，兩個端口與三類資源的查詢都成功且無結果"
fi
```

**預期輸出：** 只有一行，以 `A7 收尾確認通過：` 開頭。

**怎麼讀：** 區塊由上往下逐項檢查，遇到第一個問題就印 `STOP：…`，後面的查詢不再執行。

- `private`：同時看「是不是 symlink」和「是否存在」，所以指向不存在目標的 symlink 不會被當成不存在。區塊不跟隨、不讀取、不刪除它。
- `status.tsv`：`grep` 找不到異常紀錄（退出碼 1）才是好結果，有找到（0）或讀取失敗（其他值）都會 STOP。
- 端口與資源：查詢成功而且沒有結果，才算沒有。查詢失敗的狀態是 UNKNOWN，不能說成「不存在」。

**通過：** 印出 `A7 收尾確認通過：…`，就往 A8。

**不符合時：** 停止，回報 `STOP：…` 那一行。不要手動刪除任何東西，也不要把後面沒跑到的查詢單獨拿出來跑。

**對照已被 hold 過的舊 run 時：** 若某個已完成的 run 事後被 `hold`（例如 2026-11-04 11:25 那次），它的紀錄會多出 `hold_request`、`db_remove HELD`、`secrets_removed HELD`、`HOLD` 檔等內容。A7 會印出 `hold_request` 那一行並 STOP，後面的端口與資源查詢不會執行，這對這種 run 是預期結果。這是事後操作留下的紀錄，要和當次 `run` 的 `RESULT` 分開看；回報時請說明，不要刪除這些紀錄，當時已保存的收尾結果也不需要重做。

### A8. 回饋，由你決定是否驗收

**目的：** 把結果與理解整理出來，由你決定是否驗收。文件通過或 `run` 成功都不等於驗收完成。

**可以提供：** 操作時間、實際輸入的命令、每個命令的 exit code、`RESULT` 行、`status.tsv`、`demo/result.json`、A6 的計數、`checks/*.json`、`readiness.tsv`、`versions.txt`、`resources.txt`、A7 的輸出，以及 `hold-status`（若有）。

**不要上傳：** `private/` 內任何內容、秘密值、`.env`、環境變數傾印、`docker inspect` 輸出，以及未審查的原始日誌（`app.log`、`build.log`、`db-up.log`、`ops.log`、`provision.out`、`demo-console.log`、`demo/` 內的原始回應）。需要原始材料時，先留在本機等待指示。

把下面填好，交給 ChatGPT 審查：

```text
操作回報
1. 時間與環境：開始／結束時間、終端、當次 HEAD
2. 操作：preflight exit=  ／ run exit=  ／ 是否使用 hold、stop（各 exit=）
3. 成果：RESULT 行；斷言 ok 數；請求數；secret_check 結果
4. 收尾：private 是否 absent；55433／18080 查詢；container／volume／network 查詢（含 rc）
5. 我的理解：用自己的話說明 preflight 與 run 做了什麼、結果證明與不能證明什麼
6. 異常：第一個非預期結果、停在哪一步、目前保留什麼（沒有就寫「無」）
7. 文件問題：哪裡看不懂、跟實際不符或缺少
8. 我的判斷：可以驗收／暫不驗收，以及原因
```

## B. 這樣正常嗎？

四種分類：**正常**（繼續）、**預期暫態**（等待）、**不通過**（結果不合格，停止回報）、**應停止**（狀態不明或操作有問題，停止回報）。

| 你看到 | 分類 | 意思與做法 |
|---|---|---|
| `run` 一段時間沒有新行，尤其 `identity: PASS` 之後 | 預期暫態 | `build` 正在離線建置，安靜不代表卡住。等待，不要 Ctrl-C。超過 §3.6 的上限仍無新行才到 C |
| `build: PASS` 之後 `app_ready` 還沒出現 | 預期暫態 | app 啟動中，腳本每 2 秒輪詢一次，輪詢不印在畫面上 |
| `readiness.tsv` 開頭有 `NO_HTTP`、`not_ready` | 預期暫態 | app 還沒開始接受連線，只要最後一行是 `ready` 就沒事 |
| `readiness.tsv` 最後一行 `404 … ready` | 正常 | 精確的 404 是就緒訊號，不是錯誤 |
| 畫面出現 `app_ready: no rc=…` | 不通過 | 90 秒內沒拿到精確 404，或 app 程序已結束。和上面的暫態不同，腳本會自己進入保全。等 `run` 結束，回報 |
| `RESULT PASS`、`exit=0` | 正常 | 收尾已完成，往 A5。不需要 `hold`，不需要 `stop` |
| `RUN='…'` 之後什麼都沒印 | 正常 | 設定變數本來就沒有輸出，用 A5 命令 2 確認 |
| 區塊印出 `STOP：RUN …` | 應停止 | `RUN` 是空的、貼錯，或換了終端。回報，不要用最新目錄猜 |
| 訊息裡的路徑變成 `/status.tsv` | 應停止 | `RUN` 是空的。回報 |
| `assertions_failed: 0` 加 `A6 計數確認通過` | 正常 | 讀到檔案並解析成功，沒有失敗紀錄 |
| `read or parse failed: …` | 應停止 | 讀取或解析失敗，不是 0 筆 |
| 有計數但印出 `STOP：A6 計數未通過` | 不通過 | 回報實際數字 |
| `A7 收尾確認通過：…` | 正常 | 查詢都成功，而且什麼都沒有 |
| `STOP：… 查詢失敗，狀態 UNKNOWN` | 應停止 | 查詢失敗不是「不存在」。後面的查詢不再執行 |
| `STOP：private 是 symlink…` 或 `STOP：private 仍存在…` | 應停止 | 不跟隨、不讀取、不刪除，回報 |
| `run` 結束，`exit` 是 4 | 不通過 | 自動保全已確認停止，但什麼都沒移除，container、volume、`private/` 都可能還在。不要操作，回報（C4） |
| `run` 結束，`exit` 是 2 | 應停止 | 狀態 UNKNOWN，不等於資源已不存在，也不等於已停止。不要操作，回報（C4） |
| 訊息 `HELD: resources.txt items and …/private are kept` | 應停止 | 這是固定句子，不是實際查到的結果，不證明 `private` 或資源真的存在。回報時不要把它當成查證結果；實際是否存在要靠 A7 那種查詢 |
| 對已經 `RESULT PASS` 的 run 執行 `hold`，得到 exit 4 | 應停止 | 這是誤用，不是正常下一步。它會寫入 `HOLD` 與 `hold_request`，改變該 run 的紀錄，之後的結論也變成 HELD。exit 4 也不代表阻斷過任何東西。不要刪除紀錄，回報 |

## C. 異常區（正常成功者跳過）

這一節不是依序全做的步驟。先對照下表，只有第一列才做 C1～C3：

| 現在的情況 | 分類 | 做什麼 |
|---|---|---|
| `run` **還在執行**（還沒出現 `RESULT` 與 `exit=`），而且懷疑秘密外洩、畫面出現不該出現的內容，或超過 §3.6 上限仍無新行 | 執行中需要阻斷 | 做 C1～C3 |
| `run` 還在執行，只是安靜 | 預期暫態 | 不用 `hold`，繼續等（見 B） |
| `RESULT PASS`、`exit=0` | 正常成功 | **跳過整個 C**，回 A5 |
| `preflight` 失敗，或 `run` 主流程已回傳結果且 `exit` 不是 0 | 主流程已回傳的異常 | **不要 `hold`**，只看 C4 並回報 |

你不需要為了練習而製造故障，也不需要試過各種 HOLD 案例。

`hold` 是異常阻斷。它會改變 run 紀錄，並且可能中斷進行中的工作。畫面出現像秘密的文字時，不要複製、截圖或貼到任何地方，只回報類型與去識別的位置（§8）。

### C1. 在第二終端設定本次 RUN

**目的：** 第二終端的 `RUN` 是空的，因為終端 A 的變數不共享，要在這裡重新設定。

**位置：** 新開終端 B。終端 A 維持原狀，不要按 Ctrl-C。

**時間點：** 此時 `run` 還沒結束，所以路徑來自終端 A 畫面**第一行** `run directory: ` 後面。無法確認路徑時，不要對任何候選目錄操作；列出目錄的結果只是候選，不能當成本次 run。

```bash
cd /home/logos/jtm-evidence-demo-20261102/repo
RUN='把這裡換成終端 A 第一行的完整路徑'
```

**預期輸出：** 無輸出。

### C2. 送出 hold

```bash
if [ -n "${RUN:-}" ] && [ -f "$RUN/run-id.txt" ] && [ "${RUN##*-}" = "$(cat "$RUN/run-id.txt")" ]; then
  scripts/task-evidence-demo.sh hold "$RUN"; echo "exit=$?"
else
  echo "STOP：RUN 未設定、路徑不存在，或與 run-id.txt 不符；沒有送出 hold"
fi
```

等它自己結束，可能需要幾分鐘：等主程序最多 120 秒，等鎖最多 30 秒，之後還要停止 app 與 DB（§3.6）。等待時不要再下其他命令。

| 結果 | 意思 |
|---|---|
| `RESULT HELD …`、`exit=4` | 已確認停止，沒有移除任何東西 |
| `RESULT HOLD REQUESTED, stop NOT CONFIRMED (UNKNOWN)`、`exit=2` | 已要求，但停止未確認，狀態 UNKNOWN。中斷 `db_up`、`build`、`demo` 時依設計會是這個結果 |
| `exit=64` 或 `STOP：…` | 被拒絕，沒有處理任何資源。`hold` 不受版本門檻影響，工作區不乾淨不會讓它被拒絕，但 RUN 身分、路徑、鎖等原有防護仍可能拒絕 |

### C3. 之後做什麼

1. 兩個終端都不再操作。不要重跑 `run`，不要 `stop`，不要用 Ctrl-C 或關閉終端。
2. 不要自行 `docker rm`、`docker volume rm`、`rm -rf`，不要刪除 `HOLD`、`RUN` 目錄或鎖檔。
3. 依 A8 的格式回報，第 6 項寫清楚第一個非預期結果與 `exit`。
4. **破壞性處置（移除 container、volume、network、`private/`）一律另行核准。** 保全完成不代表可以清理，正式入口也沒有處置命令。

### C4. 主流程已回傳結果的異常（先回報，不追加操作）

主流程已回傳結果時，先保留現況並依 A8 回報，不要重跑。主流程退出不等於子程序、服務或伺服器端效果已停止；UNKNOWN 可能仍有子程序或服務未確認停止，不自行追加 `hold`、`stop` 或清理。

| 你看到 | 分類 | 意思與處理 |
|---|---|---|
| `exit=64`（有 `REFUSED: …` 或 `source gate refused: …`） | 拒絕 | 入口在開始前就拒絕：用法、路徑、鎖被持有、版本門檻未通過或環境覆寫。還沒有產生秘密或服務 |
| `preflight` 的 `exit` 不是 0 | preflight 停止 | 事前檢查未通過。服務與秘密都還沒有產生 |
| `run` 出現 `RESULT STOPPED before secrets/services`、`exit=3` | preflight 停止 | `run` 內的事前檢查未通過。保留畫面與 `RUN` 目錄 |
| `run` 出現 `RESULT HELD …`、`exit=4` | 自動保全 | preflight 之後的非預期失敗。已確認停止，沒有移除任何東西，資源與 `private/` 可能還在 |
| `run` 出現 `RESULT PENDING/UNKNOWN`、`RESULT HOLD REQUESTED, stop NOT CONFIRMED (UNKNOWN)`，`exit=2` | UNKNOWN | 中斷、操作或停止未確認。`exit=2` 不能當作停止或清理成功，也不代表資源不存在 |

其他 `exit` 值見 §3.7。下面是具體訊息的對照：

| 你看到 | 處理 |
|---|---|
| `rm_bootstrap_password` 或 `rm_bootstrap_pgpass` 出現 `PARTIAL_OR_FAILED` 或 `FAIL (file not confirmed deleted)` | 腳本會自行停止主流程並進入保全，不用另外下命令。等 `run` 結束，依 exit code 回報。exit 4 或 2 都可能，保全不等於已確認停止 |
| 收尾的 `secrets_removed` 出現 `PARTIAL_OR_FAILED` | 秘密目錄未確認刪除，不會是 PASS。不要手動刪除，回報 |
| 打錯命令、用法被拒絕 | 也算非預期結果：停止回報，不自行更正重輸 |
| `UNRESOLVED`，或 demo 失敗 | Task 可能存在：不猜 ID，不按 title 刪除；HTTP cleanup 不會自動執行 |

HOLD 資源留存時，下一次 `preflight` 會因既有資源而停止，這是預期的保護。

## D. 附錄：12 個請求與 41 項斷言

對照來源：`scripts/task-evidence-http-demo.py`（SHA256 `f45e44848354a3ca197ab80a9db9539cdd6eab56911ad97335175d12357c16e9`）。下表的行號只對這個版本有效。

### D1. 12 個請求

每個請求都會在 `RUN/demo/` 留下一個同編號的 JSON 檔。`{id}` 是當次 Task ID。

| # | 紀錄檔 | 方法與路徑 | 預期 | 組 |
|---:|---|---|---|---:|
| 1 | `01-create-task.json` | `POST /api/tasks` | 201 | 1 |
| 2 | `02-get-task-before.json` | `GET /api/tasks/{id}` | 200 | 1 |
| 3 | `03-create-evidence-A.json` | `POST /api/tasks/{id}/evidence` | 201 | 2 |
| 4 | `04-create-evidence-B.json` | `POST /api/tasks/{id}/evidence` | 201 | 2 |
| 5 | `05-list-page-0.json` | `GET /api/tasks/{id}/evidence?page=0&size=1` | 200 | 3 |
| 6 | `06-list-page-1.json` | `GET /api/tasks/{id}/evidence?page=1&size=1` | 200 | 3 |
| 7 | `07-duplicate-key.json` | `POST /api/tasks/{id}/evidence`，body 為 `{"content":"first","content":"second"}` | 400 | 4 |
| 8 | `08-list-size-20.json` | `GET /api/tasks/{id}/evidence?page=0&size=20` | 200 | 4 |
| 9 | `09-get-task-after.json` | `GET /api/tasks/{id}` | 200 | 5 |
| 10 | `10-delete-task.json` | `DELETE /api/tasks/{id}` | 204 | 6 |
| 11 | `11-get-task-after-delete.json` | `GET /api/tasks/{id}` | 404 | 6 |
| 12 | `12-get-evidence-after-delete.json` | `GET /api/tasks/{id}/evidence` | 404 | 6 |

### D2. 展開規則

來源有函式與迴圈，所以檢查的寫法比 41 處少，執行時才展開成 41 項，規則如下：

- 每次解析 JSON 成功時加 1 項 `<名稱>_json_dict` 或 `<名稱>_json_list`（第 98 行）。`<名稱>_json`（第 97 行）只在解析失敗時出現，不在 41 項內。
- `evidence()`（第 102–111 行）被呼叫兩次，A、B 各 6 項，共 12 項。
- 分頁迴圈（第 136–140 行）跑兩次，第 0 頁與第 1 頁各 4 項，共 8 項。
- `ev_ids_differ` 與 `pages_equal_created_objects_in_order` 不送新請求，比較的是先前的回應，所以「請求」欄寫它比較的編號。
- `createdAt` 的時間格式解析（第 110 行）不是斷言，解析失敗時 Demo 會以 ERROR 結束，不計入 41 項。

遇到第一個失敗的斷言 Demo 就停止，所以完整 PASS 才會有 41 項。

### D3. 41 項斷言（依執行順序）

| # | 斷言名稱 | 請求 | 類型 | 白話目的 | 來源行 |
|---:|---|---|---|---|---:|
| 1 | `task_201` | 1 | 狀態 | 建立 Task 回 201 | 123 |
| 2 | `task_json_dict` | 1 | 格式 | 回應是 JSON 物件 | 124 |
| 3 | `task_id_int` | 1 | 內容 | 回應的 `id` 是整數 | 125 |
| 4 | `task_before_200` | 2 | 狀態 | 讀得到剛建立的 Task | 130 |
| 5 | `task_before_json_dict` | 2 | 格式 | 回應是 JSON 物件，留作比較基準 | 130 |
| 6 | `evA_201` | 3 | 狀態 | 新增 Evidence A 回 201 | 105 |
| 7 | `evA_no_location` | 3 | 標頭 | 回應沒有 `Location` | 106 |
| 8 | `evA_json_dict` | 3 | 格式 | 回應是 JSON 物件 | 107 |
| 9 | `evA_exact_fields` | 3 | 欄位 | 恰有 `id`、`taskId`、`content`、`createdAt` | 108 |
| 10 | `evA_taskId` | 3 | 內容 | 掛在這次的 Task 上 | 109 |
| 11 | `evA_content` | 3 | 內容 | 文字等於 `Demo evidence A` | 109 |
| 12 | `evB_201` | 4 | 狀態 | 新增 Evidence B 回 201 | 105 |
| 13 | `evB_no_location` | 4 | 標頭 | 回應沒有 `Location` | 106 |
| 14 | `evB_json_dict` | 4 | 格式 | 回應是 JSON 物件 | 107 |
| 15 | `evB_exact_fields` | 4 | 欄位 | 恰有四個欄位 | 108 |
| 16 | `evB_taskId` | 4 | 內容 | 掛在這次的 Task 上 | 109 |
| 17 | `evB_content` | 4 | 內容 | 文字等於 `Demo evidence B` | 109 |
| 18 | `ev_ids_differ` | 比較 3、4 | 內容 | 兩筆 Evidence 的 ID 不同 | 133 |
| 19 | `p0_200` | 5 | 狀態 | 第 0 頁讀取成功 | 138 |
| 20 | `p0_json_list` | 5 | 格式 | 回應是 JSON 陣列 | 138 |
| 21 | `p0_one` | 5 | 內容 | 這頁只有 1 筆 | 139 |
| 22 | `p0_has_next_true` | 5 | 標頭 | `X-Has-Next-Page` 是 `true` | 139 |
| 23 | `p1_200` | 6 | 狀態 | 第 1 頁讀取成功 | 138 |
| 24 | `p1_json_list` | 6 | 格式 | 回應是 JSON 陣列 | 138 |
| 25 | `p1_one` | 6 | 內容 | 這頁只有 1 筆 | 139 |
| 26 | `p1_has_next_false` | 6 | 標頭 | `X-Has-Next-Page` 是 `false` | 139 |
| 27 | `pages_equal_created_objects_in_order` | 比較 3–6 | 內容 | 兩頁內容與建立結果相同，順序是 `createdAt` 新到舊、再 `id` 大到小 | 141 |
| 28 | `dup_400` | 7 | 狀態 | 重複 key 的 JSON 回 400 | 143 |
| 29 | `dup_json_dict` | 7 | 格式 | 錯誤回應是 JSON 物件 | 143 |
| 30 | `dup_message` | 7 | 內容 | `message` 是 `Malformed JSON request` | 143 |
| 31 | `s20_200` | 8 | 狀態 | `size=20` 讀取成功 | 145 |
| 32 | `s20_json_list` | 8 | 格式 | 回應是 JSON 陣列 | 145 |
| 33 | `s20_still_two_same_objects` | 8 | 內容 | 仍是同樣兩筆，錯誤輸入沒有多建資料 | 146 |
| 34 | `s20_has_next_false` | 8 | 標頭 | 沒有下一頁 | 146 |
| 35 | `task_after_200` | 9 | 狀態 | 新增 Evidence 後仍讀得到 Task | 148 |
| 36 | `task_after_json_dict` | 9 | 格式 | 回應是 JSON 物件 | 148 |
| 37 | `task_json_unchanged` | 9 | 內容 | Task 的 JSON 與 #2 的基準相同 | 148 |
| 38 | `delete_204` | 10 | 狀態 | 刪除 Task 回 204 | 150 |
| 39 | `delete_empty_body` | 10 | 內容 | 回應沒有 body | 150 |
| 40 | `task_404` | 11 | 狀態 | 刪除後查 Task 回 404 | 152 |
| 41 | `evidence_404` | 12 | 狀態 | 刪除後查 Evidence 回 404 | 153 |

## 參考區

以下內容操作時不用讀。§1 起的章節編號維持不變，因為腳本註解會引用它們。

Implements [task-evidence-http-demo-spec-v1.0.md](task-evidence-http-demo-spec-v1.0.md) for commit `6ce1ca5d277fa864c33e75ccfecfdafb3790741d`. Revision: D1 r8 (r7 + three-state process status RUNNING/ENDED/UNKNOWN, parent-held operation lock and explicit go handoff, NOT_STARTED distinguished from started-and-ended).

**Status (updated 2026-11-04, D2 operating-guide batch):** V1/V2 were restricted, secret-free verifications of extracted functions; V3 (real DB stage), V4d (offline build reuse, DB, app, original HTTP demo with 41 assertions, normal close-out via a one-time driver) and V5b (single-entry CLI cases A normal run, B stop on a completed run, C external hold, D stop on a held run, E one-time authorized disposal) were executed and accepted. The original V5 attempt stopped at S7 (rc 1) and that record stands. Failure paths not exercised by these cases remain unverified. Updating this document executed nothing and does not complete D2. The historical text below (r4–r8, written before any execution) is kept as written, except for the superseded items listed in §3.11.

**Original r4–r8 status: prepared, not executed.** No build, Maven, tests, Docker, database, application start, HTTP request, native library load or secret generation was run while writing this. Only static checks (syntax parsing, text review) were done; `bash -n` and `ast.parse` prove only that the files parse. libpq capability, secret safety, cleanup, containment and upgrade procedures are all **not executed**. A real run needs its own authorization. Nothing here shows that the environment is reproducible, that the HTTP behaviour was observed, or that the security measures work as described.

## 1. Source basis

| Topic | Path | Finding |
|---|---|---|
| Entry point | `src/main/java/.../JavaTaskManagerPortfolioApplication.java` | plain `@SpringBootApplication` |
| Settings | `src/main/resources/application.yml` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (no default), `SERVER_ADDRESS`, `SERVER_PORT`; Flyway enabled; JPA `ddl-auto: validate` |
| Versions | `pom.xml`, root `compose.yaml` | Java 21, Spring Boot 3.5.16, flyway-core, pgJDBC `42.7.12` (`postgresql.version`); `postgres:17.11-alpine` |
| Schema | `db/migration/V1__…`, `V2__…` | `tasks`; `task_evidence` with FK `ON DELETE CASCADE` |
| Task delete / errors | `task/api/DeleteTaskController.java`, `ApiErrorResponse.java` | `204` with no body; error fields `timestamp,status,error,message,path` |
| Test guard | `src/test/java/.../support/testdb/GuardedTestDatabase.java` | active only on annotated tests; nothing under `src/main` |

The full application has **no** guard. The TCP identity check (stage `identity`) is the protection against connecting to the wrong database.

## 2. Environment (decided)

| Item | Value |
|---|---|
| Compose project / file | `jtm-evidence-demo` / `scripts/task-evidence-demo-compose.yaml` (the root `compose.yaml` is not used) |
| Database | `task_evidence_demo`, UTF8; `password_encryption=scram-sha-256`; host logins `scram-sha-256` |
| Bootstrap role | `task_evidence_demo_bootstrap`: initialization and provisioning only; its password is set to NULL in the provisioning transaction |
| App role | `task_evidence_demo_owner`: owner of the DB and the `public` schema; NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; no memberships |
| Fixed target | DB `127.0.0.1:55433`; app `http://127.0.0.1:18080` (loopback only; no other bind address, mount, role or target) |

| File | Role |
|---|---|
| `scripts/task-evidence-demo.sh` | **the single operator entry point** (`preflight`, `run`, `hold <RUN>`, `stop <RUN>`); execute it with bash as shown; sourcing is refused on its first command line |
| `scripts/task-evidence-demo-libpq.py` | thin boundary to the official libpq (`preflight`, `provision`) |
| `scripts/task-evidence-demo-initdb.sh` | calls the libpq boundary with two file paths; no secret handling of its own |
| `scripts/task-evidence-demo-compose.yaml` | demo database only; container, volume and network carry the per-run label `jtm.demo.run-id` |
| `scripts/task-evidence-demo-secret-check.py` | local exact-value secret comparison over `<RUN>`; prints counts only (added V5, tightened V5b) |
| `scripts/task-evidence-http-demo.py` | HTTP sequence; its separate `cleanup` mode is **not** called by the entry since V5 (§3.11) |

## 3. 操作參考（不是操作入口）

操作步驟只在 A，異常處置只在 C。本章收錄環境細節、階段對照、timeout、狀態用語與歷史證據，操作時不用讀，也不要從這裡挑命令來跑。章節編號維持不變，內容已搬走的小節只留指引。

文件更新本身不代表任何交付完成。`run` 由使用者親自決定後執行，AI session 不得因本文存在而自行執行。

交付狀態（本文件提交時，2026-11-04）：D1、D2 已由使用者驗收完成；D3 的 CI、merge 與合併後核對尚未完成。版本門檻方案與驗證界線見 §3.13。

**兩個入口版本。** 下文的「D2 入口版本」指 SHA256 `fab465364ddff40932a25817d4c8099f261154586f15d267e824a0303c2b71fd`，版本門檻修改之前的入口。「D3 現行入口」指 SHA256 `f4ecff2a910ce9e248aaf073820626f0635471f544da12bf9d420455ef04f77f`，加上版本門檻後的入口。兩者的證據不能互相套用。

三類內容不要混用：

| 類別 | 意思 | 在本章的位置 |
|---|---|---|
| 現行設定 | 目前腳本實際的值 | A、C、§3.1–§3.8 |
| 已接受證據 | 歷史上實跑並被接受的案例，只支持該次情境 | 下表 |
| 尚未驗證 | 只由讀程式得知，或從未實際觸發 | 標明「僅來源推得」，並見 §3.13 |

| 已接受證據 | 支持什麼 | 不支持什麼 |
|---|---|---|
| V1／V2 | 抽出的函式在受限、無秘密環境下的控制流程 | 真實 DB 或完整流程 |
| V3 | 真實 DB 階段 | app 與 HTTP |
| V4d | DB、app、41 項 HTTP 斷言、正常收尾（一次性 driver） | 正式 CLI 入口 |
| V5b A／B | 正式 CLI `run` rc 0；對已完成 run 執行 `stop` rc 0 | 重複執行的穩定性 |
| V5b C | 外部 `hold` 在 `db_up: PASS` 之後、`db_health: PASS` 之前送出，hold rc 4、run rc 4 | 只支持已記錄的觸發順序，不證明精確執行時點，也不代表其他階段的 hold |
| V5b D | 對 HOLD run 執行 `stop` rc 4，資源與秘密都保留 | 其他未確認狀態 |
| V5b E | 一次性授權處置 rc 0，依序移除並確認 ABSENT | 這是當時的一次性 driver，不是正式 CLI 功能 |

以上不代表全面故障覆蓋、產品測試或全面安全通過；一次成功或語法通過也不代表工具通用可靠。

**版本界線。** 上表都是 2026-11-04 入口修改**之前**的證據。該次修改（§3.13 第 1 類）當時只做過 `bash -n` 與無秘密的片段控制流程驗證，所以上表不能當成 D2 入口版本或 D3 現行入口的實跑驗證。

**D2 入口版本的一次正常 run（2026-11-04）。** 下列是使用者提供並經 ChatGPT 審查的紀錄，不是文件批次重新量測：

- 入口 SHA256 `fab465364ddff40932a25817d4c8099f261154586f15d267e824a0303c2b71fd`；操作區間 11:11:16～11:33:50 CST，無排除。
- 正常 run 11:18:52～11:19:23，exit 0；12 個請求、41 項成功斷言、0 失敗，result PASS；secret check 兩次 CLEAN。
- 正常收尾後 `private/` 不存在，兩個端口與三類資源的查詢都無輸出且 rc=0。
- 11:25 另對已完成的 run 執行 `hold`，exit 4。這和前述正常成功分開，不視為執行中阻斷驗證。
- 先前有一次在 zsh 使用 `compgen`，首個錯誤後仍繼續的流程偏離，此紀錄保留，不改寫成流程完全通過。
- 這只支持一次正常路徑，不支持失敗路徑、重複執行穩定性或環境可重現。當時 D2 因文件及操作體驗尚未驗收，之後另行驗收完成。

**版本門檻之後。** 上述 run 用的是 D2 入口版本（上列 SHA256）。截至本文件提交時，D3 現行入口只有不啟動服務的代表驗證，沒有完整 Demo 回歸，所以上述紀錄不能當成 D3 現行入口的實跑驗證（§3.13）。

### 3.1 環境與工具參考

**終端與位置。** 本批觀察到的環境是 WSL2、登入 shell 為 zsh；腳本以 `#!/bin/bash` 執行，不得 `source`。正常路徑只需一個終端（A2～A7）；第二終端只用於 C。

- `preflight` 與 `run` 的來源門檻未通過時拒絕（exit 64），條件見 A2 與 §3.13。
- 不要設定 `RUNROOT`，使用預設 `~/.local/state/jtm-evidence-demo`。若有設定，`hold`／`stop` 必須用同一個值。

**必要工具。** 腳本不安裝也不升級任何東西；缺少就停止。

| 項目 | 要求 | `preflight` 會查嗎 | 版本 |
|---|---|---|---|
| `bash git python3 curl java timeout ss mvn docker awk sed ldd realpath stat flock find` | 在 PATH 上 | 只查是否存在 | 未預先核實，以該次紀錄為準 |
| Python | ≥ 3.8，只用標準函式庫 | 是（libpq 邊界） | 該次 JSON 的 `python` 欄位 |
| libpq | 固定路徑 `/usr/lib/x86_64-linux-gnu/libpq.so.5`，`PQlibVersion` ≥ 170000，需要的符號齊全 | 是 | 該次 JSON 的 `libpq_version` |
| psql | `/usr/lib/postgresql/17/bin/psql`，root 擁有，載入同一份 libpq | 是 | `<RUN>/versions.txt` |
| Docker Compose | v2，且 `docker` 查詢可成功 | 是 | 未知 |
| JDK | `pom.xml` 指定 Java 21 | 只查 `java` 存在 | `<RUN>/java-version.txt` |
| Maven | 離線建置（`mvn -o`），依賴須已在本機快取 | **否** | `<RUN>/mvn-version.txt` |
| DB 映像 | `postgres:17.11-alpine` 須已在本機（`--pull never`） | **否** | 映像 tag |

`preflight` 不查的項目，要到 `run` 建立秘密之後才會失敗，而那時的失敗會進入 HOLD。映像的唯讀確認已放在 A3。

`run` 另外會拒絕下列環境（exit 64，尚未寫入任何東西）；`preflight` 不查這些，所以 `preflight` 通過不代表 `run` 不會拒絕：`DOCKER_HOST DOCKER_CONTEXT DOCKER_CERT_PATH DOCKER_TLS_VERIFY DOCKER_CONFIG MAVEN_OPTS MAVEN_ARGS MAVEN_CONFIG JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS` 有設定（只查名稱，不讀值）、`~/.mavenrc`、`/etc/mavenrc` 或 repo 內的 `.mvn` 存在。

**專用資源與影響。**

| 資源 | 值 | 操作影響 |
|---|---|---|
| Compose project | `jtm-evidence-demo`，檔案 `scripts/task-evidence-demo-compose.yaml`（不使用根目錄 `compose.yaml`） | 已存在同名 project 資源時 `preflight` 停止，不接管 |
| Container／volume／network | 由本次 run 建立，帶 label `jtm.demo.run-id=<16 hex>`，ID 記在 `<RUN>/resources.txt` | 正常完成會移除；HOLD 時保留 |
| DB 端口 | `127.0.0.1:55433` → container 5432 | 只綁 loopback；已被占用時停止 |
| App 端口 | `http://127.0.0.1:18080` | 只綁 loopback；已被占用時停止 |
| DB／角色 | `task_evidence_demo`；bootstrap `task_evidence_demo_bootstrap`；app `task_evidence_demo_owner` | 只存在於本次 volume，只含合成資料 |
| Run 目錄 | `~/.local/state/jtm-evidence-demo/run-<時間>-<16 hex>/`（`<RUN>`，在 repo 外） | 保留，不自動刪除 |
| 固定鎖 | `/home/logos/jtm-evidence-demo-20261102/demo-fixed-resources.lock` | 每次取得追加一行擁有者紀錄；不刪除、不改權限 |
| `target/` | `mvn -o -B -q -DskipTests package` 寫入（受 Git 忽略） | 覆寫既有建置產物；測試只編譯不執行 |

**不要做的事。** 不要讀取或顯示 `<RUN>/private/` 內容，不要對 demo container 執行 `docker inspect`（container 環境含 bootstrap 密碼），不要印出環境變數或 `/proc/<pid>/environ`，也不要用 `bash -x`。

### 3.2 preflight 檢查內容

操作見 A3。它檢查：HEAD、工具、libpq 載入來源／版本／符號、psql 載入同一份 libpq、`pom.xml` 的 pgJDBC 版本、Compose v2、沒有本 project 的既有資源、55433 與 18080 沒有 listener。

`python libpq preflight rc=0 {…}` 的 JSON 含 `"result": "OK"`、`pg_env_removed`（只有名稱）、`libpq_version` ≥ `170000`，`libpq_mapped` 是 `libpq_realpath` 那一個檔案。相容性問題的處理見 §7.3。

### 3.3 run 階段對照

操作見 A4、A5。路徑結尾的 16 位十六進位就是本次 run id，`<RUN>/run-id.txt` 內容應與它相同。

**畫面遺失時。** 最新的目錄或時間相近，都不足以認定是本次 run，不能憑此設定 `RUN`，也不能憑此操作。

1. 找回本次保存的完整路徑或 run id，例如終端捲動紀錄或自己的筆記。
2. 需要列出目錄時，結果只是候選；只有當某個候選的目錄名與 `run-id.txt`，都和本次保存的 run id 相同，才算確認。
3. 無法可靠關聯本次操作就停止求助，不猜測，也不對任何候選操作。

**順序與應看到的行。** 下表的畫面行是依來源整理的（僅來源推得）；V5b A 已確認最終 profile，未逐字比對這份清單。`build` 階段安靜等待是正常的。

| # | 畫面行 | 這一步做什麼 | 主要紀錄（在 `<RUN>` 下） |
|---|---|---|---|
| 1 | `run directory: <RUN>` | 通過來源門檻並核對環境；驗證 run root；取得固定鎖（不等待、不接管）；建立 run 目錄 | `run-id.txt`、`run-source.txt`、`main.pid`、`control.lock`、`ops/` |
| 2 | `preflight: PASS` | 同 §3.2，但會寫紀錄 | `status.tsv`、`libpq-python.json`、`versions.txt` |
| 3 | `toolchain: PASS` | 記錄 Java 與 Maven 版本 | `java-version.txt`、`mvn-version.txt` |
| 4 | `secrets: PASS` | 建立 `private/`（`0700`）與四個 `0600` 一次性秘密檔 | 秘密檔的預定存放位置是 `private/`（其他通道見本節「一次性秘密」） |
| 5 | `db_up: PASS` | 啟動 demo DB（`--pull never`）；記錄實際 ID 並核對 label | `resources.txt`、`db-up.log` |
| 6 | `db_health: PASS` | 等 DB healthy；只接受 `starting` 為中間狀態 | `status.tsv` |
| 7 | `rm_bootstrap_password: DELETED rc=0` | 刪除 bootstrap 密碼檔；未確認刪除就停止主流程並進入保全（C4） | `ops/` |
| 8 | `provision: PASS` | 用 libpq 建立受限 app 角色（SCRAM），轉移擁有權，bootstrap 密碼設為 NULL | `provision.out` |
| 9 | `secret_check_1: PASS` | 比對 `<RUN>` 內（不含 `private/`、`checks/`）是否有 app 與 bootstrap 密碼實值或 SCRAM verifier 格式 | `checks/secret-check-1.json` |
| 10 | `rm_bootstrap_pgpass: DELETED rc=0` | 刪除 bootstrap pgpass；未確認刪除就停止主流程並進入保全（C4） | `ops/` |
| 11 | `identity: PASS` | 以 app 角色經 TCP 登入，輸出必須與預期單行逐字相同 | `identity-expected.txt`、`identity-actual.txt`、`identity-rc.txt` |
| 12 | `build: PASS` | 離線建置 | `build.log`、`build-artifact.sha256` |
| 13 | `app_ready: yes` | 啟動 app；`GET /api/tasks/9223372036854775807` 回 404 才算就緒 | `readiness.tsv`、`app.log` |
| 14 | `demo: rc=0` | HTTP 案例：12 個請求、41 項斷言，遇第一個非預期即停 | `demo/`、`demo-console.log`、`demo-rc.txt` |
| 15 | `secret_check_2: PASS` | 同範圍再比對 app 密碼實值與 verifier 格式（bootstrap 檔已刪，不再比對其值） | `checks/secret-check-2.json` |
| 16 | `app_stop: yes` | 只對本 run 已驗證的 app 送 SIGTERM，等待結束；沒有 SIGKILL | `status.tsv` |
| 17 | `db_stop: STOPPED rc=0` | 停止本 run 的 container | `ops/` |
| 18 | `remove_container`／`remove_volume`／`remove_network: rc=0 after=ABSENT` | 只移除 OWNED 項目，逐項重新分類 | `status.tsv` |
| 19 | `db_remove: PASS` | 三項都確認 ABSENT | `status.tsv` |
| 20 | `secrets_removed: PASS` | DB 全部確認不存在後才刪 `private/` | `status.tsv` |
| 21 | `RESULT PASS: <RUN>`，`exit=0` | 依目前狀態重新計算結論 | — |

**一次性秘密。** `secrets` 階段用 Python `secrets` 產生 bootstrap 與 app 兩個隨機密碼，寫入 `bootstrap_password`、`bootstrap_pgpass`、`app_password`、`pgpass`。你不需要輸入、查看或保存任何秘密。

下列是設計目標與已知範圍，不是「秘密絕不外洩」的保證：

- **秘密檔的預定存放位置：** `<RUN>/private/`。腳本的設計目標是不把秘密值寫到畫面或其他 `<RUN>` 紀錄。
- **檔案以外的通道：** 秘密值也會經過其他地方，例如 bootstrap 密碼在程序環境與 Docker／container 設定中，app 密碼在 app 程序環境中，兩者都會進入程序記憶體；app 密碼的 SCRAM verifier 會存在 DB catalog。完整清單見 §5.1、§5.2。
- **`secret_check` 的範圍：** 只掃 `<RUN>` 下的一般檔案，排除 `private/` 與 `checks/`，只在 `secret_check_1`、`secret_check_2` 兩個時點執行，只比對實值與 verifier 格式。
- **`secret_check` 的限制：** 不查編碼或變形後的值，不查畫面、Docker、程序環境、記憶體或 `<RUN>` 以外的位置，也不涵蓋比對之後才產生的紀錄。通過只表示該範圍、該時點沒有命中，不等於沒有外洩。

剩餘風險見 §6。

### 3.4 成果判讀

操作與判讀見 A6，逐項斷言見 D。`RESULT PASS` 表示 demo rc 0、app 確認停止、紀錄中的 DB 資源確認不存在、`private/` 確認不存在，且沒有中斷或 HOLD。規格見 [task-evidence-http-demo-spec-v1.0.md](task-evidence-http-demo-spec-v1.0.md)。

### 3.5 正常收尾

操作見 A7。A7 用的查詢與 `preflight` 形式相同。

### 3.6 Timeout（現行設定）

以下是現行腳本的值，單位為秒。timeout 被觸發是要回報的非預期結果，不要自行放寬或重跑。

| 階段 | 現行值 |
|---|---|
| `preflight` | libpq 邊界 30；`ldd`、`psql --version`、`ss` 各 10；`docker compose version` 與每個 Docker 列表各 20 |
| `toolchain` | `java -version`、`mvn -v` 各 30 |
| `secrets` | 建目錄 10；建檔 20 |
| `db_up` | 120；之後的 `compose ps` 20、`docker inspect` 10 |
| `db_health` | 總計 60，每 3 秒查一次，每次 inspect 10 |
| 刪秘密（`rm_bootstrap_*`、`private/`） | 各 30 |
| `provision` | 外層 60；連線 5；statement 10 |
| `secret_check_1`／`_2` | 各 60 |
| `identity` | 20；連線 5；statement 5 |
| `build` | 180 |
| `app_ready` | 總計 90，間隔 2；每次 curl 連線 2、總計 5 |
| `demo` | 總計 120；每請求連線 3、總計 15，程序備援 20 |
| `app_stop` | 等待 30（沒有 SIGKILL） |
| `db_stop` | 60（`docker stop -t 30`） |
| `remove_*` | 各 60；分類查詢各 10 |
| `hold` | 等主程序 120；等固定鎖 30；HOLD 下等 ops 確認 30 |
| `stop` | 固定鎖被持有時，送信號後等 30 |
| 內部 | control lock 10；操作子程序等待 `go` 10 |

- **歷史值：** r8 文字曾寫 up 180、health 120、build 900、demo 600、cleanup 120。V5b 已刻意縮短，舊值不再適用（§3.11）。
- **已驗證：** V5b A 在現行值下完成過一次；2026-11-04 D2 入口版本的正常 run 也完成過一次（約 31 秒，使用者紀錄）。
- **尚未驗證：** 餘裕有多少、其他機器負載下是否足夠。單次耗時只是參考，不是預估保證。

### 3.7 狀態用語

不同層級的詞不要混用。

| 層級 | 值 | 意思 |
|---|---|---|
| 程序 | RUNNING | `/proc` 可讀、啟動時間相符、不是 Z／X |
| | ENDED | 記錄的那個程序已不在（或已結束未回收）；**不代表其子孫程序或伺服器端效果已結束** |
| | UNKNOWN | 身分缺失，或無法讀取／解析。不是 ENDED，也不是 RUNNING |
| 操作（`ops/`） | NOT_STARTED／RUNNING／CONFIRMED／UNKNOWN (…) | 見 §4.1；被終止的 `may-close` 操作維持 UNKNOWN |
| 資源 | ABSENT／OWNED／FOREIGN／UNKNOWN | **查詢失敗一律是 UNKNOWN，不能當成不存在**；只有 OWNED 會被移除 |
| DB | NOT_CREATED／ABSENT／STOPPED／RUNNING／UNKNOWN | 由 `db_state` 重新計算 |

單次讀取只是快照。結論（exit code）在 `run`、`stop`、`hold` 都用同一規則，依當下狀態重新計算：

| Exit | 結論 | 你要做什麼 |
|---|---|---|
| `0` | PASS：demo rc 0、app 確認停止、DB 資源與 `private/` 確認不存在、沒有中斷或 HOLD | 做 A5～A8 |
| `1` | FAIL：失敗，且本次建立的東西確認已移除 | 停止，回報。依來源，`run` 在 preflight 後的失敗都走 HOLD，一般不會出現此值（僅來源推得） |
| `2` | PENDING／UNKNOWN：中斷、操作或停止未確認、移除未確認、歸屬不明；或已要求 hold 但停止未確認 | 停止，不刪任何東西，回報 |
| `3` | STOPPED：在秘密與服務產生之前停止 | 回報；只有 run 目錄的紀錄存在 |
| `4` | HELD：已要求 hold，所有操作確認，app 已結束，container 已停止或不存在 | 依 C3 等待決定 |
| `64` | 拒絕：用法、不安全路徑、鎖被持有、來源門檻未通過（只有 `preflight`、`run`）、環境覆寫、非紀錄中的 run | 停止並回報，打錯命令也一樣，不自行更正重輸（C4）。`stop`／`hold` 被拒時沒有處理任何資源 |

### 3.8 HOLD 與 stop 的語意

何時使用與命令只在 C，這裡只說明行為。`run` 自身在 preflight 之後遇到任何非預期失敗，也會自動走同一條路（寫入 `HOLD`，不移除）。

`hold` 的動作：

1. 寫入 `HOLD`，之後不再啟動任何一般操作。
2. 對本 run 仍存活且身分相符的操作／app 根程序送一次 SIGTERM。
3. 等主程序最多 120 秒；主程序未確認結束時 exit 2，不動 DB。
4. 等固定鎖最多 30 秒；取得才停止 app 與 DB container，否則什麼都不操作。
5. 重新計算結論：exit 4，或 exit 2 `HOLD REQUESTED, stop NOT CONFIRMED (UNKNOWN)`。

中斷 `db_up`、`build`、`demo` 或 Docker 移除時，依設計會是 exit 2：這些命令的子孫程序無法驗證已結束。被中斷操作的 DB／HTTP 效果都是 UNKNOWN。

**HOLD 保留什麼：** 已停止的 container、volume、network、`private/`、`HOLD`、`resources.txt`、`ops/`、`status.tsv` 與其他紀錄。沒有任何東西會被移除，也沒有撤銷或輪替。

**`stop` 的實際語意。** 它是對**已結束** run 的收尾，不是停止命令，也不是強制清理；沒有 SIGKILL，不會碰 FOREIGN／UNKNOWN，也不會解除 HOLD。

| 情況 | `stop` 的行為 | 依據 |
|---|---|---|
| 主程序不是 ENDED | 拒絕（exit 64）；要阻斷請用 `hold` | 來源 |
| 固定鎖被持有 | 對本 run 仍存活的已記錄根程序送 SIGTERM，等 30 秒；仍持有就什麼都不操作，exit 2 | 來源 |
| 已完成的 run | 重新核對，不重建，exit 0 | V5b B |
| HOLD run | 不移除任何東西，exit 4 或 2 | V5b D（exit 4） |
| 有 `interrupted` 紀錄 | 不移除，維持 exit 2 | 僅來源推得 |
| 未中斷且條件全部符合的未完成收尾 | 繼續移除 OWNED 項目，全部 ABSENT 後才刪 `private/` | 僅來源推得 |

因為這最後一種情況會移除資源，`stop` 絕不能當成阻斷手段（§8）。正常 PASS 後不需要 `stop`。

**Ctrl-C 或關閉終端的後果。** 這會留下 `interrupted` 紀錄：腳本會嘗試停止 app 與 DB，但不移除，結論為 exit 2，之後 `stop` 也不會移除（僅來源推得）。

**HOLD 之後的原則。** 操作上的做法見 C3。

- V5b E 是當時的一次性授權動作，不是可重複使用的步驟。
- 歸屬或狀態不明（FOREIGN／UNKNOWN）時不猜測、不刪除。
- `private/` 只有在紀錄中的 DB 資源全部確認不存在之後才能刪；它是復原材料。

### 3.9 異常時怎麼做

已移到 B（判斷）與 C（處置）。原則不變：首個非預期結果就停止並回報，包含打錯命令或用法被拒絕。不要修改文件、腳本、權限或環境，不要安裝任何東西，要等另行核准後才恢復操作。

### 3.10 回饋格式

已移到 A8。

### 3.11 已被取代的舊文字

下表保留舊 §3 與舊狀態段落的內容，只作歷史對照；完整舊版在 D2 文件批次的 before 副本。

| 舊文字 | 現行來源 |
|---|---|
| timeout：up 180、health 120、build 900、demo 600、cleanup 120 | up 120、health 60、build 180、demo 120；cleanup 不再自動執行（V5b 修改） |
| 只有 `secrets`、`provision`、`identity` 失敗走 hold，其他走 close-out | preflight 之後的非預期失敗一律走 hold（V5） |
| demo 失敗時自動執行 cleanup 分支 | 不自動執行，demo 失敗走 hold（V5） |
| 畫面行 `db_stop: PASS` | `db_stop: STOPPED rc=0` |
| 階段清單沒有 `toolchain`、`rm_bootstrap_*`、`secret_check_*`、`remove_*` | 見 §3.3 |
| Run ended without incident 時用 `stop` | 正常完成不需 `stop`，它只是選用的重新核對 |
| V5 pending verification | V5 在 S7（rc 1）STOP；V5b 案例 A–E 已接受 |
| `rm_bootstrap_*` 刪除失敗只留紀錄，主流程繼續 | 兩個刪除點已改為失敗阻斷並保全（2026-11-04，未實跑，見 §3.13） |
| 畫面遺失時以最新目錄與時間認定 `<RUN>` | 只能當候選，須核對本次保存的 run id（§3.3） |
| 用法打錯可更正後再輸入 | 一律停止回報（§3.9） |
| 值不會出現在畫面與 `private/` 以外的紀錄；秘密只在 `private/` | 改為有範圍的設計目標與通道說明（§3.3） |
| 入口寫死 `EXPECTED_HEAD`，`--commit` 也記這個值 | 固定應用基線與當次 HEAD 分開；`preflight`、`run` 走來源門檻（§3.13） |
| 準備兩個終端，`run` 一開始就在終端 B 設定 `RUN` | 正常路徑單一終端，`run` 結束後才設定（A5）；第二終端只用於 C（D2 文件批次） |
| 選用：完成後用 `stop` 再核對 | 正常 PASS 後不需要（A4） |
| 命令行尾帶 `#` 註解、`ls … \| wc -l` 計數 | 互動式 zsh 預設不把 `#` 當註解；管線計數會掩蓋讀取失敗。改為無註解區塊，以 Python 標準函式庫解析計數（A6） |

歷史 FAIL／STOP 不因修訂而消失：V5 S7 rc 1 的 STOP 保留；V5b 的 `pack.sh` rc 1 當時沒有產生 ZIP，後續封裝另行處理；V4d 把就緒探測改成正數不存在 ID，是因為 id 0 依來源不可能就緒；2026-11-04 `compgen` 首錯後繼續的流程偏離保留（§3 開頭）。

### 3.12 六項必要承諾

| 承諾 | 現行機制 | 本章位置 | 限制 |
|---|---|---|---|
| 秘密控制 | 秘密檔預定存放於 `private/`；`secret_check` 做有範圍的比對；bootstrap 檔刪除失敗即阻斷；DB 確認不存在後才刪 `private/` | A7、§3.3、§5 | 同使用者、root、Docker 高權限、程序記憶體、bootstrap 環境變數、container 內 local trust、未加密 loopback 不在防護內 |
| 阻斷保全 | `hold`、自動 hold、不移除 | C、§3.8、§8 | 只驗證過 V5b C／D 的情境 |
| 清理歸屬 | 紀錄 ID＋run label；只移除 OWNED；查詢失敗是 UNKNOWN | A7、§3.7、§4.2 | 破壞性處置須另行授權 |
| 一定鑑識紀錄 | `status.tsv`、`ops/`、`resources.txt`、`checks/`、`demo/` | A6、§3.3、§8 | 不保證 SIGKILL、斷電、磁碟故障時的紀錄 |
| libpq 相容性流程 | `preflight` 閘門、去秘密錯誤、升級提案 | A3、§3.2、§7 | 不自動升級；§7.5 只是計畫 |
| 可理解操作文件 | A～D | A～D | 須由使用者閱讀並回報確認；桌面走讀不證明新手可用 |

### 3.13 已知缺口與修正狀態

各項影響不同，請分開看，不要把未修項目當成已解決。

**1. 已修正，失敗分支未實跑。** `rm_bootstrap_password` 與 `rm_bootstrap_pgpass` 兩個刪除點：刪除失敗現在會阻斷主流程並進入保全，不再繼續 `provision`／`identity` 及後續步驟。

- 已確認：來源修改，以及無秘密、無 Docker／DB／app／HTTP 的片段控制流程驗證；D2 入口版本的一次正常 run 走過刪除成功的分支（使用者紀錄）。
- 未確認：真實 `rm` 失敗、完整 HOLD、Docker 或程序停止行為。
- 不新增重試、強制刪除、撤銷或輪替。保全的結果是 exit 4 或 2，依現行條件而定。

**2. 未修，會影響操作。**

- `preflight` 不查環境覆寫、本機映像與 Maven 快取，所以通過後 `run` 仍可能拒絕或進入 HOLD（§3.1）。
- 正式入口沒有 HOLD 後的處置命令，也沒有接上 HTTP `cleanup`；兩者都需要另行授權的一次性做法（C3）。

**3. 版本門檻（D3 方案 A）。** 舊的 `EXPECTED_HEAD` 同時負責拒絕後續 commit 與記錄當次版本，現在分開：

- `APP_BASELINE_COMMIT` 是固定應用基線；`RUN_HEAD` 是當次實際 HEAD，傳給 HTTP Demo 的 `--commit`。兩者都寫在 `<RUN>/run-source.txt`。
- **入口分流：** `locate_repo` 只定位 repo。`preflight`、`run` 才呼叫 `check_source_gate`，`run` 在建立 `RUN`、秘密或服務之前呼叫。`hold`、`stop` 不走這個門檻，工作區不乾淨不影響保全；它們原有的 RUN 身分、路徑、權限、鎖定、程序與資源檢查不變，仍可能拒絕。
- **拒絕條件：** HEAD 無效；基線 commit 找不到，或不是 HEAD 的祖先；已提交的 `pom.xml`、`src/main` 與基線不同；索引有 assume-unchanged、skip-worktree、未解決合併等狀態；`pom.xml`、`src/main` 有不支援的檔案型態（含 symlink）、受忽略或多餘的檔案；實際檔案內容或執行權限與基線不同；工作區有 tracked、staged 或 untracked 差異。任何查詢失敗都拒絕。受忽略的 `target/` 不單獨造成拒絕。
- **沒有繞過開關。** 沒有環境變數或參數可以略過門檻。
- **限制：** 這是開始時的一次性檢查，不能防止執行中被其他程序修改，所以操作期間不要改工作區。它不是防竄改、建置可重現或環境安全的證明，也不檢查 Git 本身或 `GIT_*` 環境。應用來源相同，不代表 Demo 工具的所有改動都已驗證。
- **驗證界線：** 截至本文件提交時，只有不啟動服務的驗證，沒有完整 Demo 回歸。門檻函式是從入口抽出，在 repo 外的合成 Git 目錄測試，不是完整 CLI 實跑。兩批結果分開看：
  - **前批（2026-11-04 16:30 批次）：** 案例 1～3 通過。案例 4a 判為不符而 STOP，當時 4b 以後的案例與入口接線審查都未驗證。這個 FAIL 與 STOP 保留，沒有改判。
  - **收尾批次（2026-11-04 16:55 時窗）：** 測試外殼補上 `RUN_HEAD=` 初始化後，重驗 4a，並補驗 4b、5a～5d、6、7、8a～8c、9a、9b、10，另加一個後繼 commit 成功案例 S1，共 15 案，結果都符合預期。案例 1～3 沒有重跑。入口接線只做靜態閱讀審查。本批結果已由使用者驗收。
  - **本文件提交時仍未驗證：** D3 現行入口實際執行 `preflight`、`run`、`hold`、`stop`，以及 `hold`、`stop` 處理門檻修改前建立的舊 RUN。
  - **提交之後：** 本文件不回寫之後的回歸或 CI 結果。這些結果以交付 PR 的紀錄為準。

**4. 文字問題。** 入口與 compose 檔的過時標頭已修正；其他腳本的標頭未逐一審查，以本文件為準。

**5. 尚未驗證。** V5b 列出的項目仍未驗證：其他失敗路徑、`may-close` 操作中途被 hold、`rec` 寫入失敗的實際觸發、重複執行的穩定性。D2 入口版本只有一次正常 run，沒有失敗路徑或執行中 hold 的回歸。截至本文件提交時，D3 現行入口還沒有完整 Demo 回歸，連正常 run 也沒有。D2 的成功不能算成 D3 的成功。

## 4. Stage reference

| Stage | Action | Fails closed when |
|---|---|---|
| session | locate repo; source gate (§3.13; `preflight` and `run` only); refuse environment overrides (§13); validate run root; take the fixed lock (never wait, never take over); unique run dir; `main.pid`; `control.lock`; `ops/`; INT/TERM/HUP trap; `ulimit -c 0` | source gate refused, override present, unsafe path, lock held (exit 64) |
| preflight | read-only checks (tools incl. `flock`/`find`; libpq boundary `preflight`; psql's libpq; pgJDBC version; compose v2; no existing project resources; ports free) | any check fails (exit 3) |
| toolchain | `java -version` and `mvn -v` recorded in `<RUN>` (timeout 30 s each) | either fails → hold |
| secrets | operations `secrets_dir` (`mkdir -m 700`) and `secrets_create` (Python, four `0600` files with `O_EXCL|O_NOFOLLOW` relative to a verified dir fd) | → hold |
| db_up | operation `db_up` (bootstrap password only in that child's environment; `db-up-attempted` is written inside the same critical section); then IDs recorded in `resources.txt` before any hold handling | up rc≠0, any ID unknown, label mismatch |
| db_health | read-only `docker inspect`; hold checked each round; only `starting` is an accepted intermediate state | 60 s, a failed inspect, or any other state → hold |
| provision | operation `rm_bootstrap_password`, then `provision` (`initdb.sh` → libpq boundary), then `rm_bootstrap_pgpass` | any failure → hold, including a bootstrap delete that is not confirmed (the next step is not started; fixed 2026-11-04, see §3.13); interrupted transaction outcome UNKNOWN |
| identity | operation `identity`: `env -i` psql, one `SELECT`; its combined stdout+stderr must equal the single expected line byte for byte, rc 0 | anything else → hold |
| build / app_ready | operation `build`; operation `app` (launched under the control lock, `-Djtm.demo.run-id=<id>`); readiness (V4d) = `GET /api/tasks/9223372036854775807` with `Accept: application/json`, curl exit 0 and HTTP exactly 404 (source: id ≤ 0 is rejected with 400 by GetTaskByIdService, so `/api/tasks/0` can never be ready; a positive unknown id reaches the repository and maps to 404). Each attempt is logged in `<RUN>/readiness.tsv` (attempt, time, curl rc, 3-digit status or NO_HTTP, process state, decision) | build error, no exact 404 within 90 s, readiness log write failure |
| secret_check_1 / secret_check_2 | local exact-value comparison over `<RUN>` after provisioning and after the demo (§13) | hit or tool error → hold |
| demo | operation `demo`; the HTTP cleanup branch is not run automatically since V5 | demo rc≠0 → hold |
| finalize | §4.2 | — |

### 4.1 Operations, records and hold coordination (G2, G3)

Every state-changing external command — compose `up`, provisioning, identity, build, app, demo, cleanup, `docker stop/rm`, `volume rm`, `network rm`, and **every secret file operation** (`mkdir private`, create, `rm` of `bootstrap_password`, `bootstrap_pgpass` and `private/`) — is one *operation*, started only by `dispatch()` (the app by the same pattern):

- Under the run's `control.lock`: check `HOLD` (a `normal` operation is refused once a hold exists; only `contain` = DB stop is allowed), create a new record `ops/NNN-<stage>.op` with exclusive create (never reused, never overwritten, never deleted), start `timeout <n> <command>` in the background with the control-lock descriptor closed, write `pid` and process start time into the record, release. A hold therefore either prevents the start or finds the operation recorded.
- The operation's own subshell takes `ops/NNN-<stage>.lock` (fd 7) before `exec timeout`; the command and every descendant that keeps fd 7 hold that lock.
- **Start handoff (r8):** `created` (record exists) → the **parent** opens and locks `NNN-<stage>.lock` on fd 7 (`locked`) → the child is forked; it inherits fd 7, closes the control-lock fd, and waits (≤ 10 s) for a `go` line before doing any work → the parent records `meta` (PID and start time, validated) and writes `go` only if both are valid (otherwise `handoff_failed`, no `go`, the child exits 125 without running the command) → the parent closes its own fd 7 copy (no unlock) and releases the control lock → it waits; `ended rc=…` is appended when the end is observed. Because the parent holds the lock before the child exists, a free operation lock can never mean "the child has not locked yet". The app uses the same handoff.
- Appended events: `created`, `locked`, `meta`, `go` / `handoff_failed`, `ended rc=…`, `signaled by=…` / `signal_failed`, `settle …`. States: **NOT_STARTED** (no `go`, the end was observed by `wait`, lock free — the command was never run), **RUNNING**, **CONFIRMED** (root ENDED, lock free, `go` present, and ended by itself, or terminated and class `inherit`), **UNKNOWN (…)** (identity missing/invalid, handoff incomplete, process state unreadable, lock held or unreadable, or a terminated `may-close` operation). States are re-evaluated at every check; records are never deleted or marked done.
- **Process status (r8)** is three-valued. RUNNING: `/proc/<pid>/stat` is readable, parses, the start time equals the recorded one, and the state is not `Z`/`X`. ENDED: same start time with state `Z`/`X` (exited but not yet reaped — this says nothing about its descendants or server-side effects); or a readable entry with a different start time (the PID now belongs to another process — only the recorded process is known to be gone); or `/proc` itself is readable and `/proc/<pid>` is absent. UNKNOWN: PID or start time missing or malformed, the entry exists but cannot be read, or it cannot be parsed. `kill -0` is not used for status. A single read is a snapshot and not race-free; ENDED of the root alone never confirms an operation (the lock and class rules above still apply). Signals are sent only when the status is RUNNING for a valid recorded identity; for the app the command line must also match.
- Class `inherit` (by source review, all processes keep inherited descriptors): `mkdir`, `rm`, the secret-creating Python, provisioning (`initdb.sh` → `timeout` → libpq boundary; no subprocesses in the boundary), identity `psql`, the app (`src/main` contains no `ProcessBuilder`/`Runtime.exec`). Class `may-close`: compose `up`, `mvn`, the HTTP demo (its `curl` runs via Python `subprocess`, which closes inherited descriptors), every `docker` CLI operation. A terminated `may-close` operation stays UNKNOWN: stopping the `timeout` process is not taken as proof that the whole operation stopped.
- A containment `db_stop` is its own record; it never replaces or clears the record of the operation that was in progress. A failing stop leaves the earlier UNKNOWN in place.
- Secret files and HOLD: a delete operation that has not started when `HOLD` is written is not started (`NOT_RUN (hold requested; files kept)`). A delete already running when the hold arrives may have removed part of the data: afterwards the script records exactly which named paths still exist (`DELETED`, or `PARTIAL_OR_FAILED … still_present: …`); deleted data is never described as recoverable. No revocation or rotation is performed.
- Remaining direct (non-operation) actions, all read-only or record-only: preflight checks, Docker listings/inspects, readiness `curl`, and writing records (`status.tsv`, `ops/`, `resources.txt`, `run-id.txt`, `main.pid`, `app.jar`, `app.op`, `demo-rc.txt`, `identity-*`, `hold-status`, `HOLD`). They inherit the fixed lock (§4.3) and end within their own timeouts.

### 4.2 Close-out order and resumable removal

1. Removal is allowed only when: no `HOLD`, no interruption, **every** operation record is CONFIRMED, app stop is `yes` or `not-started`, DB stop is `STOPPED`/`ABSENT`/`NOT_CREATED`, and the demo outcome is determined (demo rc 0, cleanup rc 0, or app never started; the entry no longer runs `cleanup` since V5, so that alternative is inactive). Otherwise PENDING; nothing removed.
2. Each recorded resource is classified on its own: ABSENT, OWNED, FOREIGN or UNKNOWN (a failed query is never ABSENT).
3. Order: container, then volume and network; ABSENT counts as done; only OWNED is removed; FOREIGN/UNKNOWN untouched; every removal is re-classified and recorded, so a later `stop` resumes.
4. `private/` is removed (as an operation) only after every recorded DB resource is confirmed ABSENT; otherwise it is kept as recovery material. PASS requires `secrets_removed PASS`.

### 4.3 Fixed lock lifecycle and mutual exclusion (G1)

One lock for the fixed project/ports, whatever `RUNROOT` is: `/home/logos/jtm-evidence-demo-20261102/demo-fixed-resources.lock` (regular `0600` file of the user, not a symlink, same inode as the open descriptor, isolation root not group/other-writable; never `chmod`ed, removed or replaced).

- `run` opens it as fd 9 and takes `flock` without waiting. Every process the script starts inherits fd 9 (dispatched operations, the app, read-only queries; `exec` keeps it). They all share **one** open file description.
- The script **never** calls `flock -u` on fd 9: an explicit unlock would release the lock for every sharer, including still-running children. It only closes its own descriptor (`exec 9>&-`) or exits. The kernel releases the lock when the last process holding that description closes it.
- Consequences per path: normal end, failure, interruption, unconfirmed hold or vanished main process all just close/exit; the lock stays held while any inheriting descendant lives, so no new `run`/`stop`/`hold` can operate meanwhile. Processes that closed the descriptor (see §4.1 `may-close`) are not covered; a free lock therefore never by itself proves all descendants ended — that is why operation records are settled separately.
- `stop` and `hold` (main process gone) open their own descriptor: `stop` takes it without waiting, or after signalling this run's verified roots waits 30 s; `hold` waits 30 s. If it is still held, they operate nothing and report UNKNOWN (exit 2). A lock is never taken over or cleared by guess.
- Covered: run vs run, run vs stop, stop vs stop, hold vs main process (hold only records and signals verified roots; the DB is operated only by the lock holder), old run records vs newer runs (labels differ → FOREIGN). Different `RUNROOT`s share the same lock.

Run root rules (before any write): `RUNROOT` must be absolute and use only `[A-Za-z0-9._/-]`; it is normalized with `realpath -m`; the result must not be inside the repository, must not contain it, and must not be inside `/home/logos/jtm-evidence-demo-20261102/evidence-*`; every existing ancestor must be a real directory owned by root or the user and not group/other-writable unless sticky; an existing run root must be a `0700` directory of the user. Unsafe directories are reported, never `chmod`/`chown`ed. The run directory is created with plain `mkdir` (fails if it exists).

## 5. Secret flow

### 5.1 Secrets and where they live

| Secret | Created by | Stored | Removed |
|---|---|---|---|
| bootstrap password | `make_secrets` (Python `secrets`) | `private/bootstrap_password` until health PASS; `private/bootstrap_pgpass` until provisioning ends | files deleted; role password set to NULL in the provisioning transaction |
| app password | `make_secrets` | `private/app_password`, `private/pgpass` | `private/` removed in `finalize` when allowed |
| SCRAM verifier of the app password | libpq `PQencryptPasswordConn` inside the provisioning process | never on disk by these scripts; in that process's memory and the SQL sent on the provisioning connection; then in the server catalog (`pg_authid`) | buffers overwritten and freed in the process; catalog entry removed with the volume |

The verifier is treated as sensitive: anyone who holds it can attempt offline guessing and, with the stored keys, impersonate the server side of SCRAM.

`<RUN>` is outside the repository and outside any evidence directory. Secrets must never be copied into Git, Issues, PRs, CI, Notion, ZIP packages, screenshots, AI chats or any third party.

### 5.2 Processes and channels that touch a secret

| Process | Secret | Channel | Exposure notes |
|---|---|---|---|
| Python `make_secrets` | both passwords | generated in memory → files | path is the only argv item |
| bash (`run`) | bootstrap password | `$(cat …)` command substitution | bash memory for the duration of the `up` command |
| `timeout`, `env`, `docker compose` CLI | bootstrap password | process environment | `/proc/<pid>/environ` readable by the same user and root |
| Docker daemon / container config | bootstrap password | container environment | visible with `docker inspect` to anyone with Docker access until the volume/container is removed; useless after `PASSWORD NULL` |
| postgres initdb (in container) | bootstrap password | image entrypoint | image behaviour; not read locally |
| libpq boundary (`provision`) | app password (fstat-verified fd); bootstrap password via libpq reading `/proc/self/fd/<n>` of the verified pgpass; verifier | ctypes buffers; libpq; TCP 127.0.0.1:55433 | SCRAM login (no password on the wire); verifier sent inside one `CREATE ROLE` statement over unencrypted loopback TCP (`sslmode=disable`): visible to root/packet capture on this host |
| PostgreSQL server | verifier | SQL, catalog | see §5.3 |
| host psql (identity) | app password via `passfile=` in the conninfo | file read by libpq; `env -i` empty environment | no value on argv or stdin; no inherited `PG*` |
| Java app (pgJDBC) | app password | `DB_PASSWORD` environment | `/proc/<pid>/environ`; Spring may expose environment through misconfiguration (actuator not part of this demo) |

Design goal, not a guarantee: no secrets on argv, in `set -x` output, in `echo`/`printf` to a terminal or log, in `status.tsv`, `provision.json` or any other `<RUN>` file outside `private/`. The scope and limits of the local check are in §3.3.

### 5.3 "Sent on stdin / over the connection" is not the same as "not logged"

r3 sent the statement on psql stdin. That only keeps it off argv; the server can still log the statement text. r4 sends it through libpq on a connection that first sets, for that session only (bootstrap is superuser): `log_statement='none'`, `log_min_error_statement='panic'`, `log_min_duration_statement=-1`, `log_min_duration_sample=-1`. Each `SET` must succeed or provisioning stops before the verifier is created. Remaining paths that are **not** controlled: `pg_stat_activity` shows the running statement to the same role and superusers while it runs; extensions such as `pg_stat_statements` or `auto_explain` would see it if preloaded (the demo image does not configure them; not verified); server crash dumps; a modified server configuration. Only the statement buffer and the escaped literal are built in ctypes memory; PQerrorMessage / PQresultErrorMessage are never read or printed, notices are discarded by a no-op notice processor.

### 5.4 Connection environment and credential sources (r5)

A `passfile` parameter does not stop libpq from using an inherited `PGPASSWORD` (it takes precedence over the password file), nor `PGSERVICE`/`PGSERVICEFILE`/`PGSYSCONFDIR`/`PGOPTIONS` and similar settings.

- libpq boundary: the first action of `main()` (also when it is called alone) removes every `PG*` name from its own environment without reading values; the output lists the removed names. Connection parameters fix host, port, database, user, `sslmode=disable`, `gssencmode=disable`, `require_auth=scram-sha-256`, `connect_timeout`, `passfile`, `options`. No other credential source and no fallback exists.
- Identity check: `env -i PATH=/usr/bin:/bin LC_ALL=C` for psql, plus `-X` (no psqlrc) and explicit conninfo with the same restrictions.
- Java app: `PG*`, `SPRING_*`, `DB_*`, `SERVER_*`, `LOGGING_*`, `MANAGEMENT_*` and JVM option variables are unset before start; pgJDBC receives the password from `DB_PASSWORD` only.
- Secret files are checked after opening (`fstat`: regular file, owner, no group/other bits, one link) relative to a directory fd that was itself checked; secret locations inside the repository or an evidence directory are refused. This narrows path-swap races; it does not defend against a compromised same-user account or root.

## 6. Memory and residual limits

- Python cannot guarantee that every copy of a secret is erased. The boundary reads the password with `os.readv` into a ctypes buffer, keeps libpq's raw `char *` results as integers, overwrites owned buffers with `memset` before `PQfreemem`, and never creates a Python `str`/`bytes` of a secret value. Copies inside libpq, OpenSSL, the kernel socket buffers, allocator free lists, swap, hibernation files and core dumps are outside its control.
- Any process running as the same user with `ptrace` rights, or as root, can read process memory and `/proc/<pid>/environ`. This design does not defend against a compromised local account or root.
- The app receives its password through the environment because the existing application only reads `DB_PASSWORD`; changing that is product code and out of scope.
- Core dumps: `run` sets `ulimit -c 0` for its own process tree only. System-wide crash handlers (e.g. `systemd-coredump`, `apport`) and the Docker daemon are not changed and may behave differently; check them before an authorized run.

## 7. libpq compatibility and controlled upgrade

Reference for later manual comparison: PostgreSQL 17 docs, *libpq Miscellaneous Functions* (`https://www.postgresql.org/docs/17/libpq-misc.html`) — `PQencryptPasswordConn`, `PQfreemem`, `PQlibVersion`.

### 7.1 Which component is which

| Component | Uses libpq? | How it is checked | Notes |
|---|---|---|---|
| Python provisioning process | yes, via ctypes | `PQlibVersion()` and `/proc/self/maps` inside that process | the only reliable answer for this process |
| host psql (identity) | yes, dynamically linked | `ldd` on the real binary must resolve to the same file | `psql --version` reports psql, **not** the libpq that Python loads |
| container `pg_isready` / postgres | container's own libpq 17.11 | image tag | used only for health (no login) |
| Java app | **no** — pgJDBC 42.7.12 implements SCRAM itself | `pom.xml` | pgJDBC SCRAM support exists since 42.2.0 |

### 7.2 Preflight design (before any secret or service)

- Library: only `/usr/lib/x86_64-linux-gnu/libpq.so.5`; real file must be in that directory, regular, root-owned, not group/world-writable; never the repo, the current directory, `LD_LIBRARY_PATH` or user input. `LD_PRELOAD`, `LD_LIBRARY_PATH`, `LD_AUDIT`, `PYTHONPATH`, `PYTHONHOME` present (names only are checked) → stop. Python must run with `-I -B`.
- After `dlopen` the set of mapped `libpq.so*` files in `/proc/self/maps` must equal exactly that real file; otherwise stop ("load source cannot be identified").
- Symbols: `PQlibVersion`, `PQconnectdbParams`, `PQstatus`, `PQfinish`, `PQserverVersion`, `PQsetNoticeProcessor`, `PQencryptPasswordConn`, `PQescapeLiteral`, `PQfreemem`, `PQexec`, `PQresultStatus`, `PQclear`; any missing → stop.
- Version: `PQlibVersion() ≥ 170000` (demo policy: same major as the demo server or newer). The functional floor for SCRAM is libpq 10 (`100000`); that is the cause of `SCRAM authentication requires libpq version 10 or above`, and it is **not** a security-maintenance target.

### 7.3 When a compatibility error happens

The scripts print only: result, stage (`environment`, `loader`, `symbols`, `version`, `connect`, `session_logging`, `verifier`, `role_setup`), a fixed category, and version facts (`libpq_version`, `server_version`, paths, Python version). No SQL, connection string, environment dump, `PQerrorMessage` or exception text. They never retry, never switch to md5/password/trust, never install or upgrade. The operator stops and opens the upgrade proposal below.

### 7.4 Upgrade proposal (to be written before any change; executing it needs separate authorization)

1. Identify what is actually affected: the process (Python boundary, host psql, a container client, pgJDBC), its binary and the library file it maps.
2. Inventory: package and repository source (`dpkg -S`, apt source), reverse dependencies, ABI/soname (`libpq.so.5`), load path, other projects and services on this host using the same library, and processes that must be restarted.
3. Candidates: currently supported PostgreSQL majors from the official versioning policy at the time of the proposal; prefer the distribution/PGDG package already in use; list change scope, risks (ABI, TLS defaults, behaviour changes) and a rollback (pinned previous package version, how to reinstall it, what to restart).
4. Approve → change in a controlled window → record.

### 7.5 Verification plan after an approved upgrade (plan only)

1. New process: `preflight` shows the expected real file, `PQlibVersion`, and all symbols; psql resolves to the expected file.
2. Isolated demo environment: SCRAM verifier stored (`rolpassword` begins with `SCRAM-SHA-256$`, checked by a query that returns only a boolean), TCP login of the owner works, md5 is not used.
3. Error paths: missing symbol / low version / wrong library path stop with the expected category (simulated in an isolated environment, not on shared libraries).
4. Role attributes and identity check unchanged; the existing Task demo and required regression tests pass.
5. Review of all `<RUN>` outputs and logs for secret values; confirm no authentication downgrade. Only then record completion.

## 8. Security incident procedure

1. **Suspected vs confirmed.** A suspicion (secret-looking text in a log, a wrong file mode, an unexpected process) is recorded as *suspected*. It becomes *confirmed* only with evidence that a value was exposed outside its allowed place.
2. **Contain without destroying evidence.** Use `hold` as shown in section C (semantics: §3.8): it records the request, blocks further dispatch, stops only this run's verified in-flight command and app, lets the fixed-lock holder stop the DB container, and keeps volume, `private/`, records and logs. A recorded request is not containment until the recomputed verdict is HELD (exit 4); exit 2 means the stop is not confirmed and the situation stays UNKNOWN. Never use `stop` (it may remove resources) as the containment step. Stop copying, packaging and sending anything. Do not reproduce the value anywhere, including in the report or an AI chat. Report what is kept (resources.txt IDs, `private/`), the remaining risk, and which disposal steps await authorization.
3. **Preserve de-secreted facts.** Timeline, commit, script versions/hashes, affected processes and files (paths only), stage results and exit codes.
4. **Raw evidence stays local.** If raw material is needed it stays in a restricted local location (`0700`), is never sent to AI or packaged, and memory dumps, core dumps or sensitive raw logs are collected only with explicit authorization.
5. **Assess.** Severity, scope (which secret, which systems, since when, who could read it) and root cause; anything not known is written as unknown.
6. **Propose remedies.** Fix, revoke/rotate and recovery steps, each executed only with authorization. For this demo the usual remedy, once investigation no longer needs them, is to remove the held run's recorded container, volume, network and `private/` (which invalidates both demo passwords); nothing is shared with other systems. The scripts never do this automatically for a held run.
7. **Recover and verify** according to the incident: old secret no longer works, new secret works, old connections closed, the weakness fixed.
8. **Prevent recurrence.** Turn the root cause into a concrete control plus a check that proves it.
9. **Limits.** Deleting a message or a file is not rotation. Data already sent to an AI or third-party service cannot be claimed deleted.

If an AI session (or a reviewer) notices a possible secret: stop the related reading and packaging, do not repeat the value, report only the type and a de-secreted location, and do not revoke, delete or rotate on its own.

## 9. Later architecture evaluation (not planned work, no commitment)

Hardware-backed keys, an OS secret store, a dedicated secret manager, TLS on the demo connection, and future security standards (including post-quantum options) are recorded only as items for a later assessment through Observe → Assess → Compare → Spike → Verify → Approve → Adopt → Record. No service, hardware, cost, external transfer or compliance claim is introduced here.

## 10. User supervision contributions (requirements raised by the user)

These are requirements the user raised while supervising this work. They are not claims of verified personal technical skill and are not test results. The "Still to verify" column is r5-era text kept as written; current evidence and limits are in §3 and §3.12.

| User requirement | Design impact | Source completed (r4 → r5) | Still to verify (none executed) |
|---|---|---|---|
| End-to-end secret protection; no unauthorized third party | secret table and channel list (§5); no secret on argv/logs; nothing sent to AI/GitHub/CI/Notion/ZIP | §5 incl. §5.4 (r5: inherited PG* cleared, fstat-verified secret files), entry script, libpq boundary | authorized run + output review |
| Hardware storage and room for security upgrades | kept as later evaluation (§9) | §9 | — (not in scope) |
| AI accidentally meeting a secret: identify, block, recover | AI rule in §8; containment keeps evidence | §8, `hold` (r5) | tabletop review |
| Incident forensics, severity, root cause, prevention | nine-step procedure; containment separated from removal | §8, `hold` (r5) | tabletop review; hold in an isolated run |
| libpq compatibility and controlled, verified upgrade | preflight gates, de-secreted errors, upgrade proposal and verification plan | §7, libpq boundary `preflight` | §7.5 |

## 11. Evidence boundaries

- Operation spec, run records (`<RUN>`) and automated tests are different evidence. HTTP `404` after delete does not prove the DB cascade.
- Syntax checks of these scripts show only that they parse. Until an authorized run succeeds, nothing here shows reproducibility or that the security controls work.
- Update 2026-11-04: V3, V4d and V5b were authorized runs and were accepted. They support only the cases listed in §3; they do not show general reproducibility or that every control works.

## 12. Not verified (open items)

Historical list written for r4–r8, before any execution, and kept as written. Some items were later exercised by V3, V4d and V5b; this batch did not re-derive which ones. Current status: §3 and §3.13.

- Nothing in this document was executed: entry script, libpq boundary, `preflight`, `run`, `hold`, `stop`, Docker, DB, psql, Maven, app, HTTP. The r7 coordination (control lock, fixed-lock lifecycle, operation records and settle rules, signals, per-resource classification) was reviewed by reading only; `bash -n` proves only that the script parses.
- r8 process status and handoff were reviewed by reading only. Assumed: `/proc/<pid>/stat` format (state = field 3, start time = field 22, `comm` may contain `) `), same-user processes visible in `/proc` (no `hidepid` effect for own processes), `flock -E` conflict exit code, a failed `exec 7>>` redirection not terminating the non-interactive shell, `sleep 0.1` support.
- Assumed from documentation, not observed: `flock(2)` semantics (shared open file description, explicit unlock vs close); bash `exec n>>file` descriptors being inherited (not close-on-exec); `timeout` forwarding `SIGTERM`; bash `wait` returning on a trapped signal; `/proc/<pid>/stat` field positions; Python `subprocess` closing inherited descriptors; which Docker/Maven child processes keep descriptors (treated as `may-close`).
- Native signatures were declared from the official libpq API; not compared with this host's `libpq-fe.h`; the boundary was never loaded. `/usr/lib/x86_64-linux-gnu/libpq.so.5 → libpq.so.5.18` exists (file metadata only); its `PQlibVersion` is unknown. `require_auth` needs libpq ≥ 16 (gate is 17).
- libpq reading `passfile=/proc/self/fd/<n>` (its permission check uses `stat`, which follows to the opened file) — documented behaviour assumed, not observed.
- psql `-q -A -t -c` printing only the row; `options='-c statement_timeout=5s'` accepted for the owner role.
- `docker compose ps -a -q`, label interpolation with `${DEMO_RUN_ID:?}` for services/volumes/networks, Go templates used for IDs and labels.
- `/usr/lib/postgresql/17/bin/psql` path and its `ldd` output; postgres image behaviour (`POSTGRES_HOST_AUTH_METHOD`, `--auth-host`, `command`, local `trust`).
- `os.readv` into a ctypes buffer, `c_void_p` argument passing, `del os.environ[...]` reaching libpq's `getenv` on this Python 3.10.
- Jar name assumed from the artifactId; build environment (`MAVEN_OPTS`, `~/.m2/settings.xml`) not controlled.
- `PQlibVersion ≥ 170000` is a demo compatibility policy, not a security-maintenance certification.

## 13. V5 changes to the entry (single entry point)

- `run` refuses Docker/Maven/Java override variables (`DOCKER_HOST`, `DOCKER_CONTEXT`, `DOCKER_CERT_PATH`, `DOCKER_TLS_VERIFY`, `DOCKER_CONFIG`, `MAVEN_OPTS`, `MAVEN_ARGS`, `MAVEN_CONFIG`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`), `~/.mavenrc`, `/etc/mavenrc` and a repo `.mvn` (names/existence only; exit 64).
- Records `java -version` and `mvn -v` in `<RUN>` with their exit codes checked; compose `up -d --pull never`; build `mvn -o -B -q -DskipTests package` (offline; tests compiled, not run) and the jar SHA256 in `<RUN>/build-artifact.sha256`.
- DB health: only `starting` is an accepted intermediate state; a failed inspect stops (never treated as "not yet healthy").
- Local exact-value secret checks (`scripts/task-evidence-demo-secret-check.py`) after provisioning and after the demo; output in `<RUN>/checks/` (counts only). A hit or tool error stops (hold).
- Every unexpected failure after preflight goes to hold (exit 4 when the stop is confirmed, 2 otherwise); a failed demo is held and the HTTP cleanup branch is **not** run automatically.
- `stop` on an already completed run keeps the earlier confirmed secret removal (`secrets_removed PASS`) and returns the original success verdict (0) when everything is still absent.
- Expected-hold cases: `hold <RUN>` stops this run's verified processes and DB container, keeps volume, secrets and records; `stop` on a HOLD run removes nothing. Disposal of a held run's resources is a separate, explicitly authorized action, not part of `stop`.
- Limits: not all failure paths are verified; static checks prove syntax only.
- 2026-11-04 (after V5b): a failed `rm_bootstrap_password` or `rm_bootstrap_pgpass` now stops the main line through the existing `fail_stage` path. Checked only by `bash -n` and a secret-free control-flow check of the extracted call sites; not run against real `rm` failures, Docker, a DB or the app (§3.13).
