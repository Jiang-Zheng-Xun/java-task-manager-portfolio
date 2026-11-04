# Task Evidence API｜本機 HTTP Demo 操作規格 v1.0

適用實作：`6ce1ca5d277fa864c33e75ccfecfdafb3790741d`

## 定位

本 Demo 補充既有 Task CRUD Demo，展示 Evidence 建立、分頁及代表性輸入拒絕行為。AI 協作案例另行說明規劃、使用者反饋與交付過程。

本文是操作規格，未附本次新增 Demo 的實跑紀錄。以下 HTTP 狀態及欄位屬預期結果；實際執行須另存觀察結果，不能直接將本文當成執行成功證明。

6 分 30 秒可作選用的口頭導覽節奏，不要求錄影或自動錄製。

## 執行前提

執行包必須明確指定隔離工作位置、完整 commit、應用連線位址、DB 身分、允許資料操作、秘密處理及服務收尾方式。

只使用合成內容及本次建立的 Task。不得因本文存在而沿用舊環境、讀取原專案 .env，或自行取得 DB／應用啟動權限。

執行時不得與回歸測試同時操作相同 DB。每一步檢查實際 HTTP 狀態、headers 及 body；curl 成功連線不代表業務斷言通過。非預期結果停止正常序列，依核准方式保存與清理。

## 一、建立展示專用 Task

使用既有 POST /api/tasks 建立一筆合成 Task。

預期：
- HTTP 201。
- 取得實際產生的 Task ID。
- 後續請求只使用該 ID，不代入其他既有資料。

GET 該 Task，保存 Evidence 建立前的完整回應，作為欄位比較基準。

## 二、建立兩筆 Evidence

依序向 POST /api/tasks/{實際Task ID}/evidence 傳送：

{"content":"Demo evidence A"}
{"content":"Demo evidence B"}

每次預期：
- HTTP 201。
- 回應恰含 id、taskId、content、createdAt。
- taskId 符合本次 Task。
- content 符合送入值。
- 兩筆 Evidence ID 不同。
- 沒有 Location header。

ID 及時間由系統產生，不預填固定值。

## 三、讀取兩頁

依序 GET：

/api/tasks/{實際Task ID}/evidence?page=0&size=1
/api/tasks/{實際Task ID}/evidence?page=1&size=1

在只有上述兩筆 Evidence 的展示 Task 下，預期：
- 兩次均為 HTTP 200、JSON array，各一筆。
- 第一頁 X-Has-Next-Page 為 true。
- 第二頁 X-Has-Next-Page 為 false。
- 兩頁合起來恰好是剛建立的兩筆，沒有重複。
- 順序符合 createdAt DESC、id DESC；時間相同時使用 id 判斷。

不只依 A／B 標籤猜測排序，應比較實際回應。

## 四、代表性輸入拒絕

以原始 JSON 傳送重複 key：

{"content":"first","content":"second"}

預期：
- HTTP 400。
- 錯誤回應中的 message 為 Malformed JSON request。
- 未建立第三筆 Evidence。

再以 size=20 查詢同一 Task，預期仍只有原本兩筆 Evidence，且 X-Has-Next-Page 為 false。

這是一個代表案例，不替代其他 JSON、Unicode、交易或併發測試。

## 五、核對 Task 回應不變

再次 GET 本次 Task，將完整 JSON 欄位與第一節保存的基準比較。

預期：
- HTTP 200。
- Task 回應欄位相同，包括狀態與更新時間。

這是 HTTP 層觀察；資料庫欄位及交易層的不變性由另外的測試證據支持。

## 六、刪除及確認

只對本次 Task ID 執行 DELETE。

預期：
- HTTP 204，body 為空。
- 後續 GET Task 為 404。
- 後續 GET 該 Task 的 Evidence 為 404。

父 Task 不存在時 Evidence 端點回 404，不單獨證明資料庫實體列已 cascade 刪除；該行為另有 DB 測試證據。

若中斷，從保存的建立回應恢復實際 ID，只清理該 Task。不得按 title 刪除、清空資料表或猜測 ID。網路失敗不代表清理成功；未確認時保留未決狀態。

## 七、保存結果及停止服務

實跑紀錄包含：
- commit、環境識別與實際起訖時間。
- 每一步預期／實際 HTTP 狀態。
- 已去除秘密的 headers、body 與欄位比較結果。
- 本次 Task／Evidence ID。
- 清理與服務停止結果。
- 失敗、未執行項目及觀察限制。

只依核准方式停止本次啟動的服務，不刪除既有 volume，不把未知狀態記為成功。

## 與其他展示並存

- 既有 Task CRUD Demo：說明完整 Task 操作。
- 本 Evidence Demo：說明 Evidence 增量及代表性邊界。
- AI 協作案例：說明分工、使用者反饋、失敗與流程調整。

三者分別維護適用版本及證據範圍。操作規格、實跑紀錄與自動化測試不得互相冒充。
