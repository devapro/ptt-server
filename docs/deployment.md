# Deployment

Reference, organised by mechanism. If you are setting a relay up for the first time, follow
[running-your-own.md](running-your-own.md) instead and come back here for the details.

## Run in place

```bash
cd ptt-server
./gradlew run
```

Runs the server in the foreground (`server.start(wait = true)` in
[`Main.kt`](../src/main/kotlin/com/github/devapro/pttdroid/server/Main.kt)) bound to
`config.host:config.port` (default `0.0.0.0:8000`). `Ctrl-C` triggers the JVM shutdown hook
(`Main.kt:25-30`), which stops Netty with a 1s grace period and a 5s hard timeout, draining
in-flight connections rather than dropping them instantly.

Requires the Gradle toolchain to resolve **JDK 21** (`kotlin { jvmToolchain(21) }` in
[`build.gradle.kts`](../build.gradle.kts)); `settings.gradle.kts` applies the Foojay resolver
plugin so Gradle can auto-provision a matching JDK if one isn't already installed.

## Build a runnable distribution

```bash
./gradlew installDist
```

Produces a standalone runnable distribution under `build/install/PTTdroidServer/` (start scripts
+ classpath jars) via the Gradle `application` plugin (`mainClass =
"com.github.devapro.pttdroid.server.MainKt"`). Run it directly:

```bash
build/install/PTTdroidServer/bin/PTTdroidServer
```

Configuration is read from the environment at process start — see
[configuration.md](configuration.md).

## Docker

A [`Dockerfile`](../Dockerfile) and three compose files cover the three ways this gets deployed.
Copy [`.env.example`](../.env.example) to `.env` first — Compose reads it automatically, and the
TLS and ngrok overlays refuse to start without the values they need.

```bash
cp .env.example .env && $EDITOR .env
```

### Plaintext on a trusted LAN

```bash
docker compose up -d --build
curl -s localhost:8000/health
```

The default, because it is the common case: a few handsets on one Wi-Fi with no certificate to
distribute. Set `PTT_AUTH_TOKEN` even here if the network is not yours alone.

### TLS with a self-signed certificate

```bash
docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d --build
./deploy/fingerprint.sh
```

The overlay turns the plaintext connector **off** rather than merely leaving it unpublished — a
relay that speaks both is one misconfigured firewall away from carrying audio in the clear — and
publishes only `8443`.

On first boot the server generates a self-signed keypair into the `ptt-certs` volume and logs its
SHA-256 fingerprint. `deploy/fingerprint.sh` prints it again on demand. Paste it into the
client's **Settings → Security → Certificate fingerprint**, turn on **Encrypted connection**, and
save.

The volume is what makes this work across restarts: the client trusts one exact certificate, so
regenerating the keypair would lock out everyone already paired. See
[configuration.md](configuration.md#tls-and-the-self-signed-certificate).

Verify it independently at any time:

```bash
echo | openssl s_client -connect <host>:8443 2>/dev/null \
  | openssl x509 -noout -fingerprint -sha256
```

### Through an ngrok tunnel

```bash
docker compose -f docker-compose.yml -f docker-compose.ngrok.yml up -d --build
./deploy/ngrok-url.sh
```

`deploy/ngrok-url.sh` reads the agent's inspector and prints the four values to type into the
client. ngrok terminates TLS with its own publicly trusted certificate, so the client uses
**Encrypted connection on, port 443, no fingerprint** — pinning is for reaching the relay
directly. Do not stack this overlay with the TLS one.

Two things this overlay does on your behalf, because a tunnel is a public address:

- `PTT_AUTH_TOKEN` becomes **required**. Compose refuses to start without it. An ngrok URL is not
  a secret and is not access control.
- The relay's own port is republished on `127.0.0.1` only. The tunnel reaches it over the compose
  network; nothing else needs to.

Set `NGROK_DOMAIN` to a reserved domain (free accounts get one) so the address survives a
restart — otherwise every restart means re-typing a new host on every handset.

### Image details

Two-stage build: `gradle:8-jdk21` runs `installDist`, then `eclipse-temurin:21-jre-alpine` runs
it as a non-root `ptt` user. `/app/certs` is created in the image with that ownership so a named
volume mounted there stays writable. `HEALTHCHECK` runs [`docker/healthcheck.sh`](../docker/healthcheck.sh),
which probes whichever connector is enabled — over HTTPS with verification skipped when the only
one is TLS, since nothing in the image trusts a self-signed certificate it just generated.

## Deploying to a remote host

[`deploy/deploy.sh`](../deploy/deploy.sh) ships this repository to a host over SSH and brings the
compose project up there. There is no address in the script — it reads the environment, or the
same `.env`:

```bash
PTT_DEPLOY_HOST=relay.example.com \
PTT_DEPLOY_USER=ptt \
PTT_DEPLOY_COMPOSE_FILES="docker-compose.yml docker-compose.tls.yml" \
./deploy/deploy.sh
```

| Variable | Default | Meaning |
|---|---|---|
| `PTT_DEPLOY_HOST` | *(required)* | Host to deploy to |
| `PTT_DEPLOY_USER` | `$USER` | SSH user |
| `PTT_DEPLOY_DIR` | `/opt/ptt-server` | Remote directory |
| `PTT_DEPLOY_SSH_PORT` | `22` | SSH port |
| `PTT_DEPLOY_COMPOSE_FILES` | `docker-compose.yml` | Compose files, space-separated, in overlay order |
| `PTT_DEPLOY_DRY_RUN` | `0` | `1`, or `--dry-run`, prints what would run and stops |

It checks the host is reachable and has Docker before touching anything, rsyncs (excluding
`.git/`, `build/`, `.gradle/`, `.idea/` and `certs/`), brings the project up, then waits for the
container to report healthy — dumping the last 60 log lines and failing if it does not. Re-running
is the update path: the transfer is incremental and the certificate volume survives, so clients
keep their pins.

`.env` is synced deliberately: it carries the tokens the compose files need. Values already in
your environment win over the file, the way Compose treats it.

Requires only SSH and Docker on the target. Nothing is installed on it.

## Without Docker: systemd

[`deploy/ptt-server.service`](../deploy/ptt-server.service) and
[`deploy/ptt-server.env.example`](../deploy/ptt-server.env.example) run the `installDist` output
directly:

```bash
./gradlew installDist
sudo useradd --system --home /opt/ptt-server --shell /usr/sbin/nologin ptt
sudo mkdir -p /opt/ptt-server && sudo cp -r build/install/PTTdroidServer /opt/ptt-server/
sudo cp deploy/ptt-server.env.example /etc/ptt-server.env && sudoedit /etc/ptt-server.env
sudo cp deploy/ptt-server.service /etc/systemd/system/
sudo chown -R ptt:ptt /opt/ptt-server
sudo systemctl daemon-reload && sudo systemctl enable --now ptt-server
journalctl -u ptt-server -f
```

The unit is sandboxed (`ProtectSystem=strict`, `PrivateDevices`, `RestrictAddressFamilies`) with
`/opt/ptt-server/certs` as the one writable path, since that is where the keystore is generated.
`TimeoutStopSec=20s` leaves the JVM's shutdown hook room to drain connections rather than being
killed mid-transmission.

Binding below port 1024 additionally needs `AmbientCapabilities=CAP_NET_BIND_SERVICE`. Prefer a
high port with a reverse proxy in front.

## Security posture

The relay carries live microphone audio and has no accounts. Before it is reachable from anywhere
you do not control:

1. **Set `PTT_AUTH_TOKEN`.** Without it, reaching the port is the whole of access control.
   `openssl rand -base64 24`.
2. **Turn off the plaintext connector** (`PTT_HTTP_ENABLED=false`), or bind it to `127.0.0.1`.
   Otherwise the encrypted port is decorative.
3. **Cap the channels** with `PTT_MAX_SESSIONS_PER_CHANNEL` so one client cannot open sockets
   until the process falls over.
4. Prefer a **reserved ngrok domain or a real certificate** over a self-signed one if the relay
   is permanently public — pinning is excellent for a LAN and awkward to rotate at scale.

What is still not there: per-user credentials, revocation, rate limiting on the handshake, and
audit logging. This is a walkie-talkie for a group that already trusts each other, hardened
enough to survive being on the internet — not a multi-tenant service.

## CI

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs on push to `main`, tags matching
`v*`, and pull requests: sets up JDK 21 (Temurin), runs `./gradlew build` (compile + 45 tests),
uploads the test report, builds `distTar`, uploads that artifact, and
(in a second job depending on the first) builds the Docker image.

## Firewall / LAN notes

The server binds to `0.0.0.0` by default (all interfaces). For a physical Android device on the
same LAN to reach it:

- The host machine's firewall must allow inbound TCP on the configured port (default `8000`).
- The client must be pointed at the host's LAN IP — **Settings → Relay → Custom**, e.g.
  `192.168.1.20:8000` (see
  [`ptt-client-android/docs/architecture.md`](../../ptt-client-android/docs/architecture.md)); it
  is no longer a hardcoded literal in source.
- Both devices need to actually be on the same LAN/subnet (mobile data, guest network isolation,
  and VPNs are common reasons this silently fails).

## Emulator rule

**An Android emulator does not see the host machine as `localhost`.** If you run `ptt-server` on
your development machine and a client in an emulator, the client must connect to
`10.0.2.2:<port>` — the emulator's special alias for the host loopback interface — never
`localhost` and never the host's real LAN IP (that's only reachable from a physical device on the
same network, not from the emulator's virtual network). `10.0.2.2` is also what the client ships
as its **Relay → Default** (set by `relay.properties` at build time), so an unconfigured debug
build on one emulator already points at a host-machine server started with defaults. See
[`ptt-client-android/docs/build-and-run.md`](../../ptt-client-android/docs/build-and-run.md) for
the two-emulator test topology and
[`/docs/overview.md`](../../docs/overview.md) at the repo root for the full diagram.
