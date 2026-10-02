#!/usr/bin/env bash
# Run the Coselling.ai GM on the host against intergraph-compose MoM.
#
# Reads coselling-ai/.env.local (gitignored). Required there:
#   GM_OWNER_ID     local Portal user-id that created the `coselling-ai` game
#   MOM_GAME_TOKEN  credential issued for `coselling-ai` in local Portal
# Optional:
#   MOM_GM_OUTBOUND_TOKEN  token MoM presents on webhook calls; copied from the
#                          running agents-of-mind container when absent
#
# MoM runs in Docker, so it reaches this GM via host.docker.internal.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env.local ] || { echo "missing coselling-ai/.env.local" >&2; exit 1; }
set -a; . ./.env.local; set +a

: "${GM_OWNER_ID:?set GM_OWNER_ID in .env.local}"
: "${MOM_GAME_TOKEN:?set MOM_GAME_TOKEN in .env.local}"

if [ -z "${MOM_GM_OUTBOUND_TOKEN:-}" ]; then
  MOM_GM_OUTBOUND_TOKEN=$(docker inspect intergraph-agents-of-mind \
    --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | sed -n 's/^MOM_GM_OUTBOUND_TOKEN=//p')
  export MOM_GM_OUTBOUND_TOKEN
fi

export GAME_NAME=coselling-ai
export GM_PORT=${GM_PORT:-8894}
export GM_URL=${GM_URL:-http://host.docker.internal:${GM_PORT}}
export MOM_URL=${MOM_URL:-http://localhost:8080}
export APP_BASE_URL=${APP_BASE_URL:-http://localhost:${GM_PORT}}
# A player group unique to this GM: MoM prunes duplicate (owner, group) pairs,
# so sharing a human owner-id is only safe with a group no other GM uses.
export PLAYER_GROUP_ID=${PLAYER_GROUP_ID:?set PLAYER_GROUP_ID in .env.local}

exec lein run
