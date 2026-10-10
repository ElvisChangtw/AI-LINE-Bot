#!/usr/bin/env bash
# One-time host setup on a fresh Lightsail Ubuntu 24.04 instance. Run as root; safe to re-run.
# Usage: SSM_ACTIVATION_ID=... SSM_ACTIVATION_CODE=... AWS_REGION=ap-northeast-1 ECR_REPO=<acct>.dkr.ecr.<region>.amazonaws.com/ai-line-bot bash bootstrap.sh
set -euo pipefail

: "${AWS_REGION:?set AWS_REGION}" "${ECR_REPO:?set ECR_REPO}"
HOST_NAME="${HOST_NAME:-ai-line-bot}"
SSM_ROLE_MARKER=":assumed-role/ai-line-bot-ssm/"
DIR=/opt/linebot
[[ $EUID -eq 0 ]] || { echo "bootstrap: run as root" >&2; exit 1; }

echo "== packages"
# The CloudWatch alarms filter on this host name, so keep cloud-init from resetting it on reboot.
hostnamectl set-hostname "$HOST_NAME"
printf 'preserve_hostname: true\n' > /etc/cloud/cloud.cfg.d/99-preserve-hostname.cfg
apt-get update -q
apt-get install -y -q docker.io docker-compose-v2 sqlite3 curl unzip
systemctl enable --now docker

if ! command -v aws >/dev/null; then
  echo "== aws cli"
  tmp="$(mktemp -d)"
  curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "$tmp/awscliv2.zip"
  unzip -q "$tmp/awscliv2.zip" -d "$tmp"
  "$tmp/aws/install"
  rm -rf "$tmp"
fi

echo "== ssm agent (hybrid activation)"
# The agent writes its role credentials into root's [default] profile, so never run `aws configure` as root.
# Other credential sources may exist on Lightsail, so only our SSM role counts as registered.
registered() { aws sts get-caller-identity --query Arn --output text 2>/dev/null | grep -qF "$SSM_ROLE_MARKER"; }
if registered; then
  echo "already registered"
else
  : "${SSM_ACTIVATION_ID:?set SSM_ACTIVATION_ID}" "${SSM_ACTIVATION_CODE:?set SSM_ACTIVATION_CODE}"
  if snap list amazon-ssm-agent >/dev/null 2>&1; then
    snap stop amazon-ssm-agent
    /snap/amazon-ssm-agent/current/amazon-ssm-agent -register -y \
      -code "$SSM_ACTIVATION_CODE" -id "$SSM_ACTIVATION_ID" -region "$AWS_REGION"
    snap start amazon-ssm-agent
  else
    tmp="$(mktemp -d)"
    curl -fsSL "https://s3.${AWS_REGION}.amazonaws.com/amazon-ssm-${AWS_REGION}/latest/debian_amd64/amazon-ssm-agent.deb" -o "$tmp/ssm.deb"
    dpkg -i "$tmp/ssm.deb"
    rm -rf "$tmp"
    systemctl stop amazon-ssm-agent
    amazon-ssm-agent -register -y -code "$SSM_ACTIVATION_CODE" -id "$SSM_ACTIVATION_ID" -region "$AWS_REGION"
    systemctl enable --now amazon-ssm-agent
  fi
  for _ in $(seq 1 30); do
    registered && break
    sleep 3
  done
  registered || { echo "bootstrap: ssm role credentials never appeared in [default]" >&2; exit 1; }
fi
aws sts get-caller-identity --query Arn --output text

echo "== cloudwatch agent"
if [[ ! -x /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl ]]; then
  tmp="$(mktemp -d)"
  curl -fsSL "https://amazoncloudwatch-agent.s3.amazonaws.com/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb" -o "$tmp/cwa.deb"
  dpkg -i "$tmp/cwa.deb"
  rm -rf "$tmp"
fi
# On-prem the agent reads the AmazonCloudWatchAgent profile unless told otherwise.
cat > /opt/aws/amazon-cloudwatch-agent/etc/common-config.toml <<TOML
[credentials]
  shared_credential_profile = "default"
  shared_credential_file = "/root/.aws/credentials"
TOML
cat > /opt/aws/amazon-cloudwatch-agent/etc/linebot.json <<'JSON'
{
  "agent": { "metrics_collection_interval": 60, "run_as_user": "root" },
  "metrics": {
    "namespace": "CWAgent",
    "aggregation_dimensions": [["host"]],
    "metrics_collected": {
      "mem": { "measurement": ["mem_used_percent"] },
      "disk": { "measurement": ["used_percent"], "resources": ["/"], "drop_device": true }
    }
  }
}
JSON
/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m onPremise \
  -c file:/opt/aws/amazon-cloudwatch-agent/etc/linebot.json -s

echo "== app directory"
install -d -m 700 "$DIR"
printf 'ECR_REPO=%s\nAWS_REGION=%s\n' "$ECR_REPO" "$AWS_REGION" > "$DIR/deploy.env"
chmod 600 "$DIR/deploy.env"
if [[ ! -f "$DIR/app.env" ]]; then
  printf 'LINE_CHANNEL_SECRET=\nLINE_CHANNEL_TOKEN=\nOPENAI_API_KEY=\n' > "$DIR/app.env"
fi
if [[ ! -f "$DIR/tunnel.env" ]]; then
  printf 'TUNNEL_TOKEN=\n' > "$DIR/tunnel.env"
fi
chmod 600 "$DIR/app.env" "$DIR/tunnel.env"

echo "== nightly backup (backup.sh arrives with the first deploy)"
cat > /etc/cron.d/linebot-backup <<'CRON'
15 4 * * * root [ -x /opt/linebot/backup.sh ] && /opt/linebot/backup.sh >> /var/log/linebot-backup.log 2>&1
CRON

echo "bootstrap done. Fill $DIR/app.env and $DIR/tunnel.env (chmod 600 is set), then run the deploy workflow."
