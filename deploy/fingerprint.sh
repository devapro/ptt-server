#!/usr/bin/env bash
#
# Prints the SHA-256 fingerprint of the relay's TLS certificate — the value that goes into the
# client's Settings so it will trust a self-signed relay.
#
# Reads it from the running container by default. Pass a keystore path to read one on disk
# instead:  ./deploy/fingerprint.sh certs/ptt.p12
set -euo pipefail

container="${PTT_CONTAINER:-ptt-server}"

if [[ $# -ge 1 ]]; then
    keystore="$1"
    [[ -f "$keystore" ]] || { echo "No keystore at $keystore" >&2; exit 1; }
    if [[ -f "$keystore.sha256" ]]; then
        cat "$keystore.sha256"
        exit 0
    fi
    echo "No $keystore.sha256 — reading the keystore directly needs its password." >&2
    read -r -s -p "Keystore password: " password && echo >&2
    keytool -list -v -keystore "$keystore" -storepass "$password" 2>/dev/null \
        | awk -F': ' '/SHA256:/ { print $2; exit }'
    exit 0
fi

if ! docker inspect "$container" >/dev/null 2>&1; then
    echo "Container '$container' is not running. Start it, or pass a keystore path." >&2
    exit 1
fi

fingerprint="$(docker exec "$container" cat "${PTT_TLS_KEYSTORE:-/app/certs/ptt.p12}.sha256" 2>/dev/null || true)"
if [[ -z "$fingerprint" ]]; then
    echo "No fingerprint file in '$container' — is TLS enabled? (docker-compose.tls.yml)" >&2
    exit 1
fi

cat <<EOF
Certificate fingerprint (SHA-256):

  $fingerprint

In the client: Settings → Security → turn on "Encrypted connection", paste this into
"Certificate fingerprint", then Save. Colons and case do not matter.
EOF
