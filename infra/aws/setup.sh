#!/usr/bin/env bash
# Creates the AWS resources for deployment; safe to re-run. EXPECTED_ACCOUNT_ID guards against the wrong account.
# Usage: AWS_PROFILE=<personal> AWS_REGION=ap-northeast-1 EXPECTED_ACCOUNT_ID=<12 digits> ALERT_EMAIL=<you@example.com> \
#        [SSM_INSTANCE_ID=mi-...] bash infra/aws/setup.sh [--activation]
# Re-run with SSM_INSTANCE_ID once the host is registered, to limit deploys to that one host.
set -euo pipefail

: "${AWS_PROFILE:?set AWS_PROFILE to your personal profile}" "${AWS_REGION:?set AWS_REGION}"
: "${EXPECTED_ACCOUNT_ID:?set EXPECTED_ACCOUNT_ID}" "${ALERT_EMAIL:?set ALERT_EMAIL}"
export AWS_PROFILE AWS_REGION AWS_PAGER=""

GITHUB_REPO="ElvisChangtw/AI-LINE-Bot"
NAME="ai-line-bot"
HOST_NAME="ai-line-bot"
DEPLOY_ROLE="${NAME}-deploy"
SSM_ROLE="${NAME}-ssm"
TOPIC="${NAME}-alerts"
OIDC_HOST="token.actions.githubusercontent.com"

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
[[ "$ACCOUNT" == "$EXPECTED_ACCOUNT_ID" ]] || { echo "setup: profile points at $ACCOUNT, expected $EXPECTED_ACCOUNT_ID" >&2; exit 1; }
echo "account $ACCOUNT, region $AWS_REGION"

role_exists() { aws iam get-role --role-name "$1" >/dev/null 2>&1; }

echo "== ECR repository"
if ! aws ecr describe-repositories --repository-names "$NAME" >/dev/null 2>&1; then
  aws ecr create-repository --repository-name "$NAME" --image-tag-mutability IMMUTABLE \
    --image-scanning-configuration scanOnPush=true >/dev/null
fi
aws ecr put-lifecycle-policy --repository-name "$NAME" --lifecycle-policy-text '{
  "rules": [{"rulePriority": 1, "description": "keep the newest 10 images",
             "selection": {"tagStatus": "any", "countType": "imageCountMoreThan", "countNumber": 10},
             "action": {"type": "expire"}}]}' >/dev/null
REPO_URI="$(aws ecr describe-repositories --repository-names "$NAME" --query 'repositories[0].repositoryUri' --output text)"

echo "== GitHub OIDC provider"
OIDC_ARN="arn:aws:iam::${ACCOUNT}:oidc-provider/${OIDC_HOST}"
if ! aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null 2>&1; then
  aws iam create-open-id-connect-provider --url "https://${OIDC_HOST}" --client-id-list sts.amazonaws.com \
    --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1 >/dev/null
fi

TARGET_INSTANCES="arn:aws:ssm:${AWS_REGION}:${ACCOUNT}:managed-instance/${SSM_INSTANCE_ID:-*}"
[[ -n "${SSM_INSTANCE_ID:-}" ]] || echo "warning: SSM_INSTANCE_ID not set; the deploy role may run commands on every managed instance in the account" >&2

echo "== deploy role (GitHub Actions, main branch only)"
# Deploy jobs must not use `environment:`, or the sub claim stops matching the branch condition.
DEPLOY_TRUST=$(cat <<JSON
{"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
  "Principal": {"Federated": "${OIDC_ARN}"},
  "Action": "sts:AssumeRoleWithWebIdentity",
  "Condition": {"StringEquals": {"${OIDC_HOST}:aud": "sts.amazonaws.com",
                                 "${OIDC_HOST}:sub": "repo:${GITHUB_REPO}:ref:refs/heads/main"}}}]}
JSON
)
if role_exists "$DEPLOY_ROLE"; then
  aws iam update-assume-role-policy --role-name "$DEPLOY_ROLE" --policy-document "$DEPLOY_TRUST"
else
  aws iam create-role --role-name "$DEPLOY_ROLE" --assume-role-policy-document "$DEPLOY_TRUST" >/dev/null
fi
aws iam put-role-policy --role-name "$DEPLOY_ROLE" --policy-name deploy --policy-document "$(cat <<JSON
{"Version": "2012-10-17", "Statement": [
  {"Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*"},
  {"Effect": "Allow", "Action": ["ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
                                 "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage", "ecr:DescribeImages"],
   "Resource": "arn:aws:ecr:${AWS_REGION}:${ACCOUNT}:repository/${NAME}"},
  {"Effect": "Allow", "Action": "ssm:SendCommand",
   "Resource": ["arn:aws:ssm:${AWS_REGION}::document/AWS-RunShellScript",
                "${TARGET_INSTANCES}"]},
  {"Effect": "Allow", "Action": "ssm:GetCommandInvocation", "Resource": "*"}]}
JSON
)"

echo "== SSM service role (host: read ECR, write CloudWatch)"
SSM_TRUST=$(cat <<JSON
{"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": {"Service": "ssm.amazonaws.com"},
  "Action": "sts:AssumeRole",
  "Condition": {"StringEquals": {"aws:SourceAccount": "${ACCOUNT}"},
                "ArnEquals": {"aws:SourceArn": "arn:aws:ssm:${AWS_REGION}:${ACCOUNT}:*"}}}]}
JSON
)
if role_exists "$SSM_ROLE"; then
  aws iam update-assume-role-policy --role-name "$SSM_ROLE" --policy-document "$SSM_TRUST"
else
  aws iam create-role --role-name "$SSM_ROLE" --assume-role-policy-document "$SSM_TRUST" >/dev/null
fi
for policy in AmazonSSMManagedInstanceCore AmazonEC2ContainerRegistryReadOnly CloudWatchAgentServerPolicy; do
  aws iam attach-role-policy --role-name "$SSM_ROLE" --policy-arn "arn:aws:iam::aws:policy/${policy}"
done

echo "== alerts"
TOPIC_ARN="$(aws sns create-topic --name "$TOPIC" --query TopicArn --output text)"
if ! aws sns list-subscriptions-by-topic --topic-arn "$TOPIC_ARN" --query 'Subscriptions[].Endpoint' --output text | grep -qF "$ALERT_EMAIL"; then
  aws sns subscribe --topic-arn "$TOPIC_ARN" --protocol email --notification-endpoint "$ALERT_EMAIL" >/dev/null
  echo "check $ALERT_EMAIL and confirm the SNS subscription"
fi
alarm() {
  aws cloudwatch put-metric-alarm --alarm-name "$1" --namespace CWAgent --metric-name "$2" \
    --dimensions "Name=host,Value=${HOST_NAME}" --statistic Average --period 300 --evaluation-periods 2 \
    --threshold "$3" --comparison-operator GreaterThanThreshold --treat-missing-data breaching \
    --alarm-actions "$TOPIC_ARN" --ok-actions "$TOPIC_ARN"
}
alarm "${NAME}-memory" mem_used_percent 85
alarm "${NAME}-disk" disk_used_percent 80

if [[ "${1:-}" == "--activation" ]]; then
  echo "== SSM hybrid activation (valid 24h, one host)"
  aws ssm create-activation --iam-role "$SSM_ROLE" --registration-limit 1 --default-instance-name "$HOST_NAME" \
    --query '{SSM_ACTIVATION_ID: ActivationId, SSM_ACTIVATION_CODE: ActivationCode}' --output table
fi

cat <<OUT

GitHub repository variables (Settings → Secrets and variables → Actions → Variables):
  AWS_REGION      = ${AWS_REGION}
  AWS_ROLE_ARN    = arn:aws:iam::${ACCOUNT}:role/${DEPLOY_ROLE}
  ECR_REPOSITORY  = ${NAME}
  SSM_INSTANCE_ID = <mi-... shown in Systems Manager → Fleet Manager after bootstrap>
  DEPLOY_ENABLED  = true   (set last, after the host is ready)
Host bootstrap: ECR_REPO=${REPO_URI} AWS_REGION=${AWS_REGION}
OUT
