#!/usr/bin/env bash
# Deploy a Coselling.ai image to production: ./scripts/deploy.sh [sha-xxxxxxx]
#
# Refuses to run unless the image tag is already in ECR. The service stops the
# old task before starting the new one (MaximumPercent 100 / MinimumHealthy 0),
# so deploying a tag that is still being pushed takes coselling.ai down until the
# push lands — that happened on 2026-09-28 (~6 minutes of 503s).
#
# Defaults to the current HEAD's sha tag. The template is read from infra's
# origin/main (cfn/services/coselling-ai.yaml, not written yet), never from whatever branch the
# local ../intergraph/infra checkout happens to be on.
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${1:-sha-$(git rev-parse --short=7 HEAD)}"
REGION=us-west-1
REPO=intergraph/coselling-ai
IMAGE="555106000043.dkr.ecr.${REGION}.amazonaws.com/${REPO}:${TAG}"
INFRA=../intergraph/infra
[ -d "$INFRA/.git" ] || { echo "expected the infra repo at $INFRA" >&2; exit 1; }
git -C "$INFRA" fetch -q origin main
TMPDIR_CFN=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CFN"' EXIT
TEMPLATE="$TMPDIR_CFN/coselling-ai.yaml"
git -C "$INFRA" show origin/main:cfn/services/coselling-ai.yaml > "$TEMPLATE"

if ! aws ecr describe-images --region "$REGION" --repository-name "$REPO" \
       --image-ids imageTag="$TAG" >/dev/null 2>&1; then
  echo "Refusing to deploy: $TAG is not in ECR yet. Wait for the push to finish." >&2
  exit 1
fi

echo "Deploying $IMAGE using $TEMPLATE"
aws cloudformation deploy --region "$REGION" \
  --template-file "$TEMPLATE" \
  --stack-name intergraph-service-coselling-ai \
  --parameter-overrides ImageUri="$IMAGE" \
  --capabilities CAPABILITY_NAMED_IAM --no-fail-on-empty-changeset
