# AI-LINE-Bot：家族群組小幫手

家族 LINE 群組裡的查證兼問答 bot。家人引用一則轉傳訊息再 @ 它，它會上網查證，用長輩看得懂的話回「這則是真是假、為什麼、不用怕」；直接 @ 它問問題（天氣、健康常識、補助怎麼申請、手機怎麼用），它會查資料後簡短回答。

設計細節在 `docs/superpowers/specs/2026-10-09-family-fact-check-bot-design.md`。

## 在群組裡怎麼用

| 動作 | bot 的反應 |
|---|---|
| 引用一則訊息（文字、圖片、語音、影片）＋ @ bot | 查證那則；語音和影片先轉成文字（影片再從頭到尾取 6 個畫面，字幕也讀得到），超過 5 分鐘的不處理；有寫問題就針對問題回答 |
| 直接 @ bot 問問題 | 上網查資料後回答；健康、錢、法規、補助、即時資訊查不到可靠來源就不亂答 |
| 沒引用、問「這是真的嗎」或只打 @ bot | 往回找 30 分鐘內最新的一則（同一個人連續傳的會合在一起），回覆會寫看的是誰幾點傳的哪則 |
| 引用 bot 的回覆＋問題，或直接問「你為什麼說…」 | 拿 bot 剛才的回覆來解釋；發現判錯會直接更正 |
| `/imagine 內容` | 畫圖 |
| 沒 @ bot 的訊息 | 不讀內容、不回覆；文字和圖片暫存 7 天方便之後引用；語音和影片只在被問到時才下載轉文字 |

bot 只用 reply 回覆（不用 push，LINE 不收費），來不及的答案會搭下一則訊息送出。

## 架構

```
LINE ─► Cloudflare ─► cloudflared ─► app :8080 ─► OpenAI Responses API（web_search）
                      (Tunnel)        Spring Boot＋SQLite（/data）
```

- Java 21、Spring Boot 3.3、SQLite＋Flyway、`openai-java`（查證與問答）、Spring AI（`/imagine`）。
- 一次模型呼叫同時決定「查證／回答／請你引用」，來源網址只留模型真的搜尋過的（`CitationFilter`）。
- 用量上限：每日 US$2、每月 US$10（`application.yaml` 的 `factcheck.*-limit-micro-usd`）。

## 本機開發

```bash
mvn test                      # 不連外網、不用真 key（OpenAI 與 LINE 都是 WireMock）
cp .env.example .env          # 填 LINE 與 OpenAI 的 key
set -a; source .env; set +a
BOT_DB_PATH=./target/app.db BOT_MEDIA_DIR=./target/media mvn spring-boot:run
```

會花錢的真模型測試預設不跑：

```bash
mvn test -Dgroups=live -Dtest.excludedGroups=none -Dtest=EvalRunTest    # 公開謠言＋問答樣本，報表在 target/eval-*.md
```

## 部署

Lightsail（docker compose：app＋cloudflared）＋ GitHub Actions → ECR → SSM。push 到 `main` 且 repo variable `DEPLOY_ENABLED=true` 時自動部署；新版 90 秒內沒有 healthy 就自動切回上一版。

- 第一次架設：照 `docs/deploy-runbook.md` 做。
- rollback：GitHub → Actions → deploy → Run workflow，填舊的 commit sha；或在主機上跑 `sudo /opt/linebot/deploy.sh <舊 sha>`。
- 備份：主機每晚 04:15 備份 SQLite 到 `/var/backups/linebot/`，保留 7 份；另外開 Lightsail 每日 snapshot。

| 位置 | 內容 |
|---|---|
| `/opt/linebot/app.env` | `LINE_CHANNEL_SECRET`、`LINE_CHANNEL_TOKEN`、`OPENAI_API_KEY`（600） |
| `/opt/linebot/tunnel.env` | `TUNNEL_TOKEN`（600） |
| `/opt/linebot/deploy.env` | `ECR_REPO`、`AWS_REGION` |
