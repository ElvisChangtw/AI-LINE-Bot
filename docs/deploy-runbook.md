# 第一次部署 runbook

照順序做。每一步都是要本人登入或付款的操作；程式和腳本都已經在 repo 裡。

## 0. 先確認

- push `main` 之前：確認 Render 上沒有綁這個 repo、開著 auto-deploy 的舊服務（README 以前寫部署在 Render）。有的話先關 auto-deploy 或刪掉服務，否則 push 會部署舊式設定。
- 用**個人** AWS 帳號。`~/.aws` 的 `default` profile 是公司帳號，`infra/aws/setup.sh` 會拒絕它。

## 1. 網域與 Cloudflare Tunnel

1. Cloudflare dashboard → Domain Registration 買一個網域（例如 `.com`，約 US$10／年）。
2. Zero Trust → Networks → Tunnels → Create a tunnel（Cloudflared）→ 取名 `ai-line-bot` → 複製 token（`eyJ...` 那串），先存好，第 4 步要用。
3. 同一個 tunnel → Public Hostname → Add：
   - Subdomain `bot`、Domain 選剛買的網域
   - Path：`^/callback$`
   - Service：`HTTP`、URL：`app:8080`
4. Security → Bots：如果開了 Bot Fight Mode，LINE 的 POST 可能被擋成 challenge。到 Security → WAF 加一條 custom rule：`URI Path equals /callback` → Skip（勾 Bot Fight Mode 與所有 managed rules）。

## 2. AWS 資源

```bash
aws configure --profile <個人 profile>           # 或 aws configure sso --profile <個人 profile>
AWS_PROFILE=<個人 profile> AWS_REGION=ap-northeast-1 EXPECTED_ACCOUNT_ID=<12 位數帳號> ALERT_EMAIL=<你的 email> \
  bash infra/aws/setup.sh --activation
```

- 記下輸出的 `SSM_ACTIVATION_ID`、`SSM_ACTIVATION_CODE`（24 小時內有效）、`ECR_REPO`、GitHub variables。
- 去信箱點 SNS 的訂閱確認信，否則收不到告警。
- 主機還沒裝好之前，記憶體／硬碟告警會因為「沒有資料」而發 ALARM 信，這是預期的；第 3 步做完、agent 開始回報後會自己恢復 OK。

## 3. Lightsail 主機

1. Lightsail → Create instance：區域選跟上面一樣（東京 `ap-northeast-1`）、Linux／Ubuntu 24.04、方案 **US$12（2 GB、有 IPv4）**、名稱 `ai-line-bot`。
2. Networking：IPv4 和 IPv6 的 firewall 都**刪掉 HTTP 80、HTTPS 443**；SSH 22 改成「Restrict to IP address」＋勾「Allow Lightsail browser SSH/RDP」。
3. Snapshots：開「Automatic snapshots」。
4. 用 Lightsail 網頁上的 SSH 連進去，上傳 `infra/host/bootstrap.sh`（右上角齒輪 → Upload file），然後：

```bash
sudo SSM_ACTIVATION_ID=<...> SSM_ACTIVATION_CODE=<...> AWS_REGION=ap-northeast-1 \
  ECR_REPO=<第 2 步的 ECR_REPO> bash bootstrap.sh
```

註冊完成後，到 Systems Manager → Fleet Manager 找到 `mi-` 開頭的 ID，重跑一次 setup.sh，把部署權限限縮到這一台：

```bash
AWS_PROFILE=<個人 profile> AWS_REGION=ap-northeast-1 EXPECTED_ACCOUNT_ID=<...> ALERT_EMAIL=<...> \
  SSM_INSTANCE_ID=mi-xxxxxxxx bash infra/aws/setup.sh
```

## 4. 主機上的 secret

```bash
sudo nano /opt/linebot/app.env       # LINE_CHANNEL_SECRET、LINE_CHANNEL_TOKEN、OPENAI_API_KEY
sudo nano /opt/linebot/tunnel.env    # TUNNEL_TOKEN=<第 1 步的 token>
```

兩個檔案的權限已經是 600，不要改。

## 5. GitHub

Settings → Secrets and variables → Actions → **Variables**：

| 名稱 | 值 |
|---|---|
| `AWS_REGION` | `ap-northeast-1` |
| `AWS_ROLE_ARN` | 第 2 步輸出的 role ARN |
| `ECR_REPOSITORY` | `ai-line-bot` |
| `SSM_INSTANCE_ID` | Systems Manager → Fleet Manager 裡 `mi-` 開頭的 ID |
| `DEPLOY_ENABLED` | `true`（最後才設） |

然後 push `main`，或在 Actions → deploy → Run workflow。成功的話，log 最後一行是 `status=Success exit=0`。

## 6. LINE

1. 用 LINE **測試 channel** 先驗：Messaging API → Webhook URL 填 `https://bot.<網域>/callback` → Verify 要顯示 Success。
2. 實測 spec §18 列的未驗證項目：引用圖片時 `quotedMessageId`、`quoteToken` 有效期、一次回多則訊息的呈現。
3. 沒問題再改正式 channel 的 webhook，並**一定要開 Webhook redelivery**：每次部署會有 20–90 秒 app 不在線，沒開 redelivery 的話這段時間的訊息會遺失。

## 7. OpenAI

帳號加值後，跑 spike 和 eval（會花錢，大約 US$1 以內）：

```bash
set -a; source .env; set +a
mvn test -Dgroups=live -Dtest.excludedGroups=none -Dtest=OpenAiLiveSpikeTest
mvn test -Dgroups=live -Dtest.excludedGroups=none -Dtest=EvalRunTest
```

## 出事時

- 看 log：Lightsail SSH → `sudo docker compose -p linebot -f /opt/linebot/docker-compose.yml logs --tail 200 app`
- 手動 rollback：`sudo /opt/linebot/deploy.sh <舊 sha>`
- 從備份還原（備份在 `/var/backups/linebot/app-YYYY-MM-DD.db`）：

```bash
cd /opt/linebot
sudo docker compose -p linebot -f docker-compose.yml stop app
V=/var/lib/docker/volumes/linebot_app-data/_data
sudo rm -f $V/app.db-wal $V/app.db-shm
sudo cp /var/backups/linebot/app-2026-10-10.db $V/app.db
sudo chown 10001:10001 $V/app.db
sudo docker compose -p linebot -f docker-compose.yml start app
```

- 部署 log 寫「cloudflared is not running」：app 已經是新版，問題在 `tunnel.env` 的 token；改好後 `sudo docker compose -p linebot -f /opt/linebot/docker-compose.yml up -d cloudflared`。
