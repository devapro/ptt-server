# Deployment

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

A [`Dockerfile`](../Dockerfile) and [`docker-compose.yml`](../docker-compose.yml) are provided so
deployment is one command:

```bash
docker compose up --build
```

The image is a two-stage build:

1. **Build stage** — `gradle:8-jdk21`, copies the build definition first (for dependency-resolution
   caching), then sources, runs `gradle installDist`.
2. **Runtime stage** — `eclipse-temurin:21-jre-alpine`, runs as a non-root `ptt` user, copies only
   `build/install/` from the build stage. Exposes port `8000` and defines a `HEALTHCHECK` that
   polls `GET /health` every 30s.

`docker-compose.yml` maps host port `8000:8000` and sets the same `PTT_*` environment variables
as the Dockerfile's defaults (`PTT_HOST`, `PTT_PORT`, `PTT_MAX_AUDIO_FRAME_BYTES`,
`PTT_PING_SECONDS`, `PTT_MAX_CHANNEL`) — override them in the compose file or with `docker compose
run -e` as needed. `.dockerignore` excludes `.git`, `build/`, `.gradle`, `.idea`, `docs/`, and
`*.md` from the build context.

This closes the README's former "Deploy test web server" TODO on the client side — the client
README now documents that item as out of scope for the client repo, with this Dockerfile/compose
pair as the one-command deployment path instead. (The client also gained its own in-process
relay — see below — which is a separate feature, not a substitute for this deployment path.)

## CI

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs on push to `main`, tags matching
`v*`, and pull requests: sets up JDK 21 (Temurin), runs `./gradlew build` (compile + the 14
`ChannelRelayTest` cases), uploads the test report, builds `distTar`, uploads that artifact, and
(in a second job depending on the first) builds the Docker image.

## Firewall / LAN notes

The server binds to `0.0.0.0` by default (all interfaces). For a physical Android device on the
same LAN to reach it:

- The host machine's firewall must allow inbound TCP on the configured port (default `8000`).
- The client must be pointed at the host's LAN IP — set this in the client's Settings screen (see
  [`ptt-client-android/docs/architecture.md`](../../ptt-client-android/docs/architecture.md)); it
  is no longer a hardcoded literal in source.
- Both devices need to actually be on the same LAN/subnet (mobile data, guest network isolation,
  and VPNs are common reasons this silently fails).

## Emulator rule

**An Android emulator does not see the host machine as `localhost`.** If you run `ptt-server` on
your development machine and a client in an emulator, the client must connect to
`10.0.2.2:<port>` — the emulator's special alias for the host loopback interface — never
`localhost` and never the host's real LAN IP (that's only reachable from a physical device on the
same network, not from the emulator's virtual network). `10.0.2.2` is also the client's *default*
host setting out of the box, so an unconfigured debug build on one emulator already points at a
host-machine server started with defaults. See
[`ptt-client-android/docs/build-and-run.md`](../../ptt-client-android/docs/build-and-run.md) for
the two-emulator test topology and
[`/docs/overview.md`](../../docs/overview.md) at the repo root for the full diagram.
