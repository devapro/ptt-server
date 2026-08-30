# CLAUDE.md — ptt-server

Ktor WebSocket audio relay for the PTT client (`../ptt-client-android`), rewritten from a 2-file
prototype into a proper module under
`src/main/kotlin/com/github/devapro/pttdroid/server/`: `Main.kt`, `Config.kt`,
`protocol/Messages.kt`, `domain/{PttSession,PttChannel,ChannelRegistry}.kt`,
`plugins/Plugins.kt`, `routing/ChannelRoutes.kt`. Per-channel isolation and server-enforced floor
control are implemented and covered by 14 passing tests
(`src/test/kotlin/.../ChannelRelayTest.kt`). CI: `.github/workflows/ci.yml`.

Docs index: [`docs/`](docs). **[`docs/protocol.md`](docs/protocol.md) is the canonical wire
contract for this whole product — this repo owns it, the client must follow it, not the other
way around.**

## Build / test / run

```bash
./gradlew build              # compile + run tests
./gradlew run                # run the server in place, blocks in foreground
./gradlew installDist        # produce a runnable distribution (build/install/PTTdroidServer/...)
docker compose up --build    # containerized run (Dockerfile + docker-compose.yml)
```

Prerequisite: **JDK 21** (`kotlin { jvmToolchain(21) }` in `build.gradle.kts`; `settings.gradle.kts`
applies the Foojay resolver so Gradle can provision it). Dependency versions (Kotlin 2.4.10,
Ktor 3.5.2, kotlinx-serialization/coroutines 1.11.0) live in **`gradle/libs.versions.toml`** — this
repo does have a version catalog now; check that file for exact versions rather than assuming.
Details: [`docs/deployment.md`](docs/deployment.md).

## Hard rules

- Never `git commit` or `git push` unless the user explicitly asks.
- Never hardcode a client-facing assumption that only holds for one deployment (e.g. don't bake a
  specific LAN IP into server code — see `docs/configuration.md` for the env-var host/port model,
  `Config.kt`).
- **Emulators connecting to a server running on the host reach it at `10.0.2.2`, never
  `localhost`.** This matters when testing against an emulator-hosted client; it's also the
  client's default host setting.
- Keep `docs/` in sync when changing the wire protocol, routing, or session lifecycle — and
  update [`docs/protocol.md`](docs/protocol.md) *first*, since the client repo treats it as the
  spec.
- No per-audio-frame logging in the hot path (`routing/ChannelRoutes.kt`'s `handleAudio`/relay
  path) — see `resources/logback.xml`, which keeps `io.netty`/`io.ktor` at WARN/INFO precisely so
  connect/disconnect/floor events at INFO aren't buried, and so nobody is tempted to add
  per-frame logging back in.
- Run the build gate (`./gradlew build`) before declaring work done — this runs the 14
  `ChannelRelayTest` cases.
- Do not touch `gradle/` or `*.gradle.kts` unless the task is specifically about the build files —
  another workstream may own them concurrently.

## Known fixed defects — do not reintroduce

See [`docs/architecture.md`](docs/architecture.md) for the full before/after table. In short: the
old server never read the `/channel/*` wildcard (one global broadcast group for every client),
relayed frames with a serial awaited `send()` per peer inside the sender's own read loop (one
slow peer stalled everyone; a write failure to peer B disconnected sender A), and set
`maxFrameSize = Long.MAX_VALUE`. The fix for all three: `ChannelRegistry`/`PttChannel` give each
channel its own member set and floor holder, and every `PttSession` has its own bounded
`Channel<Frame>(DROP_OLDEST)` drained by a per-session writer coroutine, so fan-out is a
non-suspending `trySend` per peer.

## Not implemented

No auth, no TLS (`ws://` only) — see `docs/architecture.md`'s "Not implemented" section before
assuming otherwise.
