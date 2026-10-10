# Plan 2b：一般問答＋提問分隔符 Implementation Plan

> 使用者 2026-10-10 要求一路做完、不逐步核准；本計畫由 Claude 直接實作（Native），最後一次 whole-branch review。

**Goal:** bot 除了查證，也回答一般問題；使用者的提問放進自己的分隔符並算進快取 key（修 Plan 2 最終 review Critical 1）。

**Architecture:** 同一個 Responses API 呼叫，schema v2 多 `mode`（FACT_CHECK／ANSWER／NEED_MATERIAL）、`high_risk`、`answer_points`。分流由模型決定；程式負責分隔符、parser 一致性檢查、來源過濾、高風險沒來源改送固定文字、只快取查證結果。

**Spec:** `docs/superpowers/specs/2026-10-09-family-fact-check-bot-design.md`（2026-10-10 修訂各處）

## Global Constraints

沿用 Plan 2（`.superpowers/sdd/2026-10-09-plan2-fact-check/global-constraints.md`）：版本不升級、不覆寫 Jackson、`mvn -s ~/.m2/central-only-settings.xml`、測試不連外網、live 測試需同意、送出文字只由 `ReplyFormatter`／`BotReplies` 產生、log 不記內容、Conventional Commits、不 push。

## Review Focus

1. 提問含分隔符字串（半形／全形混用）→ 四個分隔符各剛好一次。
2. 只有「@bot」加空白 → 走規則 4，不是只送空提問。
3. 引用＋空提問 → 模型收到「使用者沒有寫問題，請查證下面的素材」。
4. `ANSWER` 但模型給了判讀、或 `FACT_CHECK` 給 `NOT_APPLICABLE` → `InvalidModelOutputException`。
5. 快取舊列（v1 JSON 沒有 `mode`）被讀到 → 當成不完整，忽略。

## Tasks

1. **分流**：`MaterialResolver` 刪規則 2、新增 `Resolution.QuestionOnly`；只有 @ 才找最近候選；`WebhookIngestService` 把 `QuestionOnly` 轉成 `material_message_id = null` 的 `FACT_CHECK` job；刪 `bot.self-material-min-chars`。依賴「@bot 真的嗎」觸發規則 3 的既有測試改成只有「@bot」。
2. **模型契約**：`ReplyMode`、`Verdict.NOT_APPLICABLE`、`FactCheckResult` 加 `mode`／`highRisk`／`answerPoints`（保留 6 參數建構子給純查證用）；schema v2、`fact-check-v2.md`、`prompt-version: v2`；`userText` 提問分隔符＋中和；parser 檢查 mode 一致性；fixture 升到 v2。
3. **回覆**：`CitationFilter` 只對 `FACT_CHECK` 降級；`ReplyFormatter` 加 `ANSWER`（300 字上限）、`NEED_MATERIAL`、高風險沒來源固定文字、`publisher` 去網址；`BotReplies` 文字改輕鬆（高風險相關除外）、新公告。
4. **服務與 job**：`FactCheckService.verify(groupId, material /* nullable */, question)`；cache key 加提問；只快取 `FACT_CHECK` 且非 `INSUFFICIENT_EVIDENCE`；`JobRunner` 支援沒素材；`V4__job_mode.sql`＋`recordFactCheck` 記 `mode`；log 加 `mode`。
5. **eval**：`samples.json` 每題可帶 `question`、`expected_mode`、`expected_high_risk`；新增分流、高風險、語氣題；報表加 mode／high_risk 欄。

每個 task：先改／寫會失敗的測試 → 跑到紅 → 實作 → 全套綠 → commit。
