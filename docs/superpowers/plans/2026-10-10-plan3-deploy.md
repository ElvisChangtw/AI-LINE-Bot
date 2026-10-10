# Plan 3：部署（Lightsail＋Cloudflare Tunnel＋GitHub Actions→ECR→SSM）Implementation Plan

> 2026-10-10 使用者要求一路做完；Claude 直接實作（Native），最後一次 whole-branch review。本計畫只產出程式與腳本，**不建立任何 AWS／Cloudflare 資源、不部署、不改 LINE 設定**，那些步驟列在「需要使用者操作」。

**Goal:** 把 bot 打包成可以在 Lightsail 上用 docker compose 跑、由 GitHub Actions 自動部署並在 health check 失敗時 rollback 的服務。

**Architecture:** compose 裡兩個 container：`app`（Spring Boot，只在 compose 內網開 8080）和 `cloudflared`（Cloudflare Tunnel，主動往外連 Cloudflare，主機不開任何 inbound port）。GitHub Actions 用 OIDC 換 AWS 臨時權限 → build image 推 ECR → `ssm send-command` 在主機跑 `deploy.sh <sha>`。

**Spec:** `docs/superpowers/specs/2026-10-09-family-fact-check-bot-design.md` §11、§13（2026-10-10 改成 Tunnel）

## 決策（2026-10-10）

- 對外入口：Cloudflare Tunnel，取代 Caddy（使用者決定；網域由使用者在 Cloudflare 買）。Tunnel 的 public hostname 只轉 `^/callback$` 到 `http://app:8080`，其他路徑在 Cloudflare 就 404。
- 主機：維持有 IPv4 的 Lightsail 2 GB（US$12）。IPv6-only 要主動連 LINE API、ECR、Docker Hub，未驗證，不冒險。
- Lightsail firewall：80、443 都關；22 只留 Lightsail browser SSH。
- body 上限改在 app 做（Tunnel 沒有 Caddy 的 `request_body max_size`）：`/callback` 沒有 Content-Length 回 411、超過 1 MB 回 413，在讀 body 之前擋。
- image 版本釘死：`maven:3.9.16-eclipse-temurin-21`、`eclipse-temurin:21.0.12.1_1-jre-noble`、`cloudflare/cloudflared:2026.9.3`。

## Tasks

1. **app 啟動與輸入防護**：刪掉沒人用、但啟動必填的 `OPEN_AI_MODEL`、`OPEN_AI_TEMPERATURE`（`spring.ai.openai.chat.options` 改成固定值）；`BotProperties`、`FactCheckProperties` 加 `@Validated`＋必要欄位約束；`/callback` body 上限 filter。測試：缺設定啟動失敗、411、413、1 MB 內照常驗簽。
2. **Dockerfile／compose**：multi-stage、非 root 使用者（uid 10001）、`/data` 屬於該使用者；compose 有 `app`＋`cloudflared`、`stop_grace_period: 45s`、`mem_limit: 1g`、log 輪替、`app-data` volume、healthcheck 打 `/actuator/health`。不 publish 任何 port。
3. **主機腳本**：`infra/host/deploy.sh <sha>`（記下目前 tag → pull → up → 60 秒內 health 不過就切回舊 tag，exit 1）、`infra/host/bootstrap.sh`（docker、SSM agent hybrid 註冊、CloudWatch agent、`/opt/linebot`、`.env` 600、每晚 SQLite 備份 cron 保留 7 份）、`infra/host/backup.sh`。
4. **AWS 腳本**：`infra/aws/setup.sh`（idempotent；強制要 `AWS_PROFILE` 且拒絕 `default`；ECR＋lifecycle 10 個、GitHub OIDC provider＋deploy role、SSM service role＋hybrid activation、CloudWatch alarm＋SNS email）。
5. **GitHub Actions**：`ci.yml`（PR／main 跑 `mvn verify`，用只有 Central 的 settings）、`deploy.yml`（main push 或手動指定 sha；`vars.DEPLOY_ENABLED == 'true'` 才跑；OIDC、不用 `environment:`；自己輪詢 `get-command-invocation` 最多 5 分鐘）。
6. **文件**：README 改寫成家族小幫手（功能、架構、本機開發、部署、rollback、需要的 secret）；spec §13 改成 Tunnel；`docs/deploy-runbook.md` 列出使用者要手動做的步驟。

## 需要使用者操作（程式完成後）

1. 在 Cloudflare 買網域；Zero Trust → Tunnels 建 tunnel，拿 token；public hostname `bot.<網域>` → `http://app:8080`，path `^/callback$`。
2. 設好個人 AWS profile（不是公司的 default），授權後跑 `AWS_PROFILE=<個人> infra/aws/setup.sh`。
3. 建 Lightsail instance，用 browser SSH 跑 `bootstrap.sh`，填 `/opt/linebot/app.env` 和 `/opt/linebot/tunnel.env`（`/opt/linebot/.env` 由 `deploy.sh` 產生，不放 secret）。
4. GitHub repo 設 variables（`AWS_ROLE_ARN`、`ECR_REPOSITORY`、`SSM_INSTANCE_ID`、`AWS_REGION`、`DEPLOY_ENABLED`）。
5. LINE console：webhook URL 改成 `https://bot.<網域>/callback`、開 redelivery。
6. OpenAI 加值 → 跑 spike 與 eval。
