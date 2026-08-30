#!/usr/bin/env bash
#
# Prints the public address of the running ngrok tunnel, translated into the four values that
# go into the client's Settings.
#
# Reads the agent's local inspector, which docker-compose.ngrok.yml publishes on loopback.
set -euo pipefail

api="${NGROK_API:-http://127.0.0.1:4040/api/tunnels}"

if ! response="$(curl -fsS --max-time 5 "$api" 2>/dev/null)"; then
    echo "Could not reach the ngrok agent at $api." >&2
    echo "Is the tunnel up?  docker compose -f docker-compose.yml -f docker-compose.ngrok.yml ps" >&2
    exit 1
fi

url="$(printf '%s' "$response" | sed -n 's/.*"public_url":"\(https:[^"]*\)".*/\1/p' | head -1)"
if [[ -z "$url" ]]; then
    echo "The agent reported no https tunnel yet. Give it a moment and retry." >&2
    exit 1
fi

host="${url#https://}"
host="${host%%/*}"

cat <<EOF
Tunnel: $url

Client → Settings:

  Host                     $host
  Port                     443
  Encrypted connection     on
  Certificate fingerprint  (leave empty — ngrok presents a publicly trusted certificate)
  Access token             the PTT_AUTH_TOKEN from .env

The relay will be reached at wss://$host/channel/<n>
EOF
