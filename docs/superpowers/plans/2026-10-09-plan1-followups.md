# Plan 1 交接：Plan 2／Plan 3 必須帶走的事項

Plan 1（地基）已完成：branch `feat/fact-check-bot`，`mvn clean test` 98/0/0。本文件記錄執行與 review 過程中延後處理的事項，Plan 2、Plan 3 撰寫時要逐條納入。

## 實作時做出的設計決定（與原 plan 不同）

| # | 決定 | 理由 |
|---|---|---|
| R3 | `MaterialRepository.recordMediaFailure` 回傳 `OptionalInt`，一條 `UPDATE … RETURNING`，只更新 PENDING | 原本找不到資料列時回傳上限值，呼叫端分不出差別；也避免晚到的失敗蓋掉已存好的圖 |
| R4 | `JobRepository.startSending` 用 CAS 搶送出權；`markWaiting`、`complete/fail/release` 加狀態條件 | 防止補救掃描和正常路徑同時送出同一筆結果 |
| R5 | reply 改用自寫的 `LineReplyClient`（JDK HttpClient），不經過 LINE SDK | SDK 的 OkHttp 會在連線中斷時自動重送 POST，造成「已送達 → 400 → 等下一則 → 重複送出」 |
| R6 | 啟動時在 Tomcat／排程開始前把 `SENDING_REPLY` 改為 `UNKNOWN`；清理改成逐筆 catch | 避免新的回覆被誤標；一個刪不掉的檔不能讓清理永久停擺 |
| R8-F1 | `complete/fail/release` 帶 attempt 編號做 fence；`job-lease` 5m、OpenAI read timeout 120s、`spring.ai.retry.max-attempts` 1 | lease 過期後舊執行緒會把新執行緒的工作打回 PENDING，付費結果遺失 |
| R8-F2/F3 | 處理 `UnsendEvent`（刪素材與檔案）、`LeaveEvent`（刪整個群組資料） | 收回的訊息不能再被當成素材送去 OpenAI |

## Plan 2 必做（查證管線）

1. **第一個 task**：收回訊息或退出群組時，如果圖片正在下載，下載完成後會留下孤兒檔。`markMedia` 改成回傳更新筆數，0 筆就刪檔。同時 `expireGroup` 的狀態清單加上 `NOT_SENT`。
2. 呼叫時間預算：每次模型呼叫的 timeout ×（重試次數 + 1）＋ backoff 必須小於 `bot.job-lease`（5m）。openai-java 的 timeout 和 maxRetries 要明確設定，不能用預設值（預設 10 分鐘 × 2 次重試）。
3. FACT_CHECK 的結果要擋 null／空字串，比照 `imagine`。否則會變成 `NOTHING_TO_SEND`，使用者 @ 了 bot 卻收不到任何回覆。
4. vision 路徑必須確認 `media_status == STORED`，不能只看 `MediaStore.storedFile` 有沒有檔案。
5. openai-java 丟出的例外訊息可能帶有 response body（裡面有素材內容）。`JobRunner.run()` 會把完整例外寫進 log，要改成只記類別名稱。
6. 「高畫質」圖片走 gpt-image-1，只會回傳 b64，`getUrl()` 是 null，結果會重試 3 次後回「這則沒查成功」。DALL·E 的網址約 1 小時失效，但待送最長會等 24 小時。`ImagineJobHandler` 要處理這兩點。
7. 只打「/imagine」沒有給 prompt 時，會落到找素材的規則。要改成回覆提示，請使用者補上要畫的內容。
8. 如果新增 enum 值：RowMapper 用 `valueOf`，rollback 到舊版時會讀到看不懂的值。sweep 要逐步隔離，避免一筆壞資料讓整個補救流程停擺。

## Plan 3 必做（部署）

1. docker compose 要設 `stop_grace_period: 40s` 以上。Docker 預設 10 秒就 SIGKILL，app 內的 graceful shutdown（30 秒）會完全沒作用。
2. Caddy 要設 `request_body max_size`（例如 1MB）。驗簽之前 body 會整包讀進記憶體。
3. 移除已經沒有程式使用、但啟動時仍是必填的 `${OPEN_AI_MODEL}`、`${OPEN_AI_TEMPERATURE}`。`BOT_DB_PATH` 和 `BOT_MEDIA_DIR` 的父目錄必須存在，而且非 root 使用者要能寫入。
4. `BotProperties` 加上 `@Validated`，缺設定時在啟動當下就失敗。
5. 更新 README（目前描述的還是翻譯 bot）。

## 要到 LINE 測試 channel 實測（需授權）

- 引用圖片時，`quotedMessageId` 是否等於該圖片的 message id
- mention 的 `index`／`length` 單位是否為 UTF-16
- `quoteToken` 的有效期，以及帶了失效 quoteToken 的 reply 是否回 400 且不消耗 token
- 一次 reply 多則訊息時，長輩實際看到的順序與呈現

## 需要使用者決定

- 規則 3（沒引用時找最近 10 分鐘內唯一的候選）要不要排除語音和影片？目前會算進去，所以「轉貼文字＋語音」之後 @bot 會回「有好幾則」。reviewer 建議排除。

## 已知、可以接受的限制

- 被移出群組後 24 小時內又被加回，可能送出離開前的舊結果。
- 部署關機時，正在送出的回覆如果超過等待時間，會變成 UNKNOWN（不補送）。
- 媒體下載的逾時只管到拿到回應為止，讀取 body 的階段只受 OkHttp 每次讀取的 15 秒逾時限制。
