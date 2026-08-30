#!/usr/bin/env bash
#
# Ship this repository to a remote host and bring the relay up there with Docker Compose.
#
# Everything is read from the environment or a .env file next to docker-compose.yml — there is
# no address in this script, deliberately. Copy .env.example to .env and fill it in, or export
# the same variables:
#
#   PTT_DEPLOY_HOST           required, e.g. relay.example.com
#   PTT_DEPLOY_USER           ssh user (default: the current user)
#   PTT_DEPLOY_DIR            remote directory (default: /opt/ptt-server)
#   PTT_DEPLOY_SSH_PORT       ssh port (default: 22)
#   PTT_DEPLOY_COMPOSE_FILES  compose files to bring up, space-separated, in overlay order
#                             (default: docker-compose.yml)
#   PTT_DEPLOY_DRY_RUN        set to 1, or pass --dry-run, to print what would run and stop
#
# Re-running is the update path: the transfer is incremental and the compose project is
# recreated in place, so the certificate volume — and every client's pinned fingerprint —
# survives.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# .env supplies defaults; anything already exported wins, the way Compose treats it. Sourcing
# the file outright would let a blank line in it silently override the value on the command line.
if [[ -f .env ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
        [[ "$line" != *=* ]] && continue
        key="${line%%=*}"
        key="${key//[[:space:]]/}"
        [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
        [[ -n "${!key-}" ]] && continue
        export "$key=${line#*=}"
    done < .env
fi

host="${PTT_DEPLOY_HOST:-}"
if [[ -z "$host" ]]; then
    echo "PTT_DEPLOY_HOST is not set. Copy .env.example to .env and fill it in." >&2
    exit 2
fi

user="${PTT_DEPLOY_USER:-$USER}"
dir="${PTT_DEPLOY_DIR:-/opt/ptt-server}"
port="${PTT_DEPLOY_SSH_PORT:-22}"
compose_files="${PTT_DEPLOY_COMPOSE_FILES:-docker-compose.yml}"
target="$user@$host"

compose_args=()
for file in $compose_files; do
    if [[ ! -f "$file" ]]; then
        echo "Compose file '$file' does not exist here." >&2
        exit 2
    fi
    compose_args+=(-f "$file")
done

dry_run="${PTT_DEPLOY_DRY_RUN:-0}"
[[ "${1:-}" == "--dry-run" ]] && dry_run=1

remote() {
    if [[ "$dry_run" == 1 ]]; then
        printf 'ssh -p %s %s %q\n' "$port" "$target" "$*"
        return 0
    fi
    ssh -p "$port" "$target" "$@"
}

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

step "Checking $target"
remote true
if ! remote "command -v docker >/dev/null"; then
    echo "docker is not installed on $host — https://docs.docker.com/engine/install/" >&2
    exit 1
fi
if ! remote "docker compose version >/dev/null 2>&1"; then
    echo "the docker compose plugin is missing on $host" >&2
    exit 1
fi

step "Syncing to $target:$dir"
remote "mkdir -p '$dir'"
# Build output and local Gradle state are large and rebuilt remotely anyway. .env is synced on
# purpose: it carries the tokens the compose files require.
rsync_args=(
    -az --delete
    --exclude '.git/'
    --exclude '.gradle/'
    --exclude 'build/'
    --exclude '.idea/'
    --exclude 'certs/'
    -e "ssh -p $port"
    ./ "$target:$dir/"
)
if [[ "$dry_run" == 1 ]]; then
    printf 'rsync %s\n' "${rsync_args[*]}"
else
    rsync "${rsync_args[@]}"
fi

step "Building and starting"
remote "cd '$dir' && docker compose ${compose_args[*]} up -d --build --remove-orphans"

if [[ "$dry_run" == 1 ]]; then
    step "Dry run — nothing was changed on $host"
    exit 0
fi

step "Waiting for the relay to report healthy"
if ! remote "cd '$dir' && for _ in \$(seq 1 60); do
        state=\$(docker inspect -f '{{.State.Health.Status}}' ptt-server 2>/dev/null || echo missing)
        [ \"\$state\" = healthy ] && exit 0
        [ \"\$state\" = unhealthy ] && exit 1
        sleep 2
    done; exit 1"; then
    echo "The relay did not become healthy. Recent output:" >&2
    remote "cd '$dir' && docker compose ${compose_args[*]} logs --tail 60 ptt-server" >&2
    exit 1
fi

step "Deployed"
remote "cd '$dir' && docker compose ${compose_args[*]} ps"

if [[ "$compose_files" == *tls* ]]; then
    step "Certificate fingerprint — paste this into the client's Settings"
    remote "docker exec ptt-server cat /app/certs/ptt.p12.sha256"
fi

if [[ "$compose_files" == *ngrok* ]]; then
    step "Public address"
    remote "cd '$dir' && ./deploy/ngrok-url.sh" || true
fi
