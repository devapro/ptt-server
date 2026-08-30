# Architecture

`ptt-server` is a Ktor/Netty WebSocket relay with per-channel isolation and server-enforced floor
control. It was rewritten from a 2-file/43-line prototype; the current tree is packaged under
`src/main/kotlin/com/github/devapro/pttdroid/server/`.

## Current shape

```
Main.kt                    -- process entry point: reads ServerConfig, starts Netty, shutdown hook
Config.kt                  -- ServerConfig, env-var parsing with clamped defaults
protocol/Messages.kt       -- wire types, JSON codec, error codes (see protocol.md)
domain/PttSession.kt       -- one connected client: bounded outbound queue + rate-limited errors
domain/PttChannel.kt       -- one channel: member sessions + floor holder, mutex-guarded
domain/ChannelRegistry.kt  -- channelId -> PttChannel, creates on join / reaps on last-leave
plugins/Plugins.kt         -- installs ContentNegotiation, WebSockets, StatusPages; wires routing
routing/ChannelRoutes.kt   -- GET /health, webSocket("/channel/{channelId}")
resources/logback.xml      -- SLF4J/Logback config
```

Tests: `src/test/kotlin/.../ChannelRelayTest.kt` — 14 cases driven through Ktor's
`testApplication`, covering channel isolation, floor control, frame validation, disconnect
cleanup, and `/health`. All passing.

## Request lifecycle

See [protocol.md](protocol.md#session-lifecycle) for the full sequence. Summary:

1. `GET /health` (`ChannelRoutes.kt:40-51`) — reads `ChannelRegistry.snapshot()`, returns
   `HealthResponse`.
2. `webSocket("/channel/{channelId}")` (`ChannelRoutes.kt:53-133`):
   - Validate `v` (if present) and `{channelId}`; reject with an `error` frame + policy-violation
     close on failure.
   - Create the `PttSession`, start its writer coroutine, join the channel via
     `ChannelRegistry.joinChannel` (creates the `PttChannel` on first join).
   - Send `welcome` first.
   - Loop `incoming`: `Frame.Binary` → `handleAudio` (size/parity check, then
     `PttChannel.relayAudio`); `Frame.Text` → `handleControl` (decode, dispatch
     `talk_request`/`talk_release`).
   - On loop exit (close/error/cancel): `ChannelRegistry.leaveChannel` (releases the floor if held,
     broadcasts `floor`+`peers`, reaps the channel if now empty), close the session's queue, cancel
     the writer.

## Concurrency model

- **`PttSession`** (`domain/PttSession.kt`) owns a `Channel<Frame>(capacity = outboundQueueSize,
  onBufferOverflow = DROP_OLDEST)`. `offer()` is `trySend` — never suspends. A dedicated writer
  coroutine per session (started in `ChannelRoutes.kt:86-98`) drains this queue into the real
  socket via `outgoing.send(frame)`.
- **`PttChannel`** (`domain/PttChannel.kt`) guards its member map and floor holder with a
  `Mutex`. Because fan-out only calls `PttSession.offer` (non-suspending), broadcasting to every
  member happens entirely inside the lock without risking a suspend-while-locked deadlock.
- **`ChannelRegistry`** (`domain/ChannelRegistry.kt`) guards the `channelId -> PttChannel` map
  with its own `Mutex`, separate from each channel's own lock. `leaveChannel` re-checks emptiness
  under the registry lock after the per-channel leave completes, so a join racing the last leave
  cannot resurrect a channel being discarded.

This design is what makes the fan-out non-blocking end to end: a slow or dead peer only fills
*its own* queue and starts dropping *its own* incoming audio; it can never stall the sender's
receive loop or another peer's delivery, and a socket write failure is caught inside that peer's
own writer coroutine, not the sender's.

## Fixed defects (compared to the pre-refactor version)

| Defect | Old behavior | Fix |
|---|---|---|
| No channel isolation | `/channel/*` wildcard never read; one global broadcast set | `{channelId}` parsed and validated (`ChannelRoutes.kt:65-72`); `ChannelRegistry` gives each channel its own `PttChannel` |
| Serial blocking fan-out | `it.send(frame)` awaited per peer inline in the sender's read loop; one slow peer stalled everyone, and a write failure to peer B disconnected sender A | Bounded per-session queue + writer coroutine (above); broadcast is `trySend`, never awaited |
| Unbounded frame size | `maxFrameSize = Long.MAX_VALUE` | `maxFrameSize = (maxAudioFrameBytes * 2).coerceAtLeast(16_384)` (`plugins/Plugins.kt:42`), plus an application-level size/parity check per audio frame |
| No structured logging | `println` on the exception path only | SLF4J + Logback (`logback.xml`); connect/disconnect/floor events logged at INFO, no per-frame logging |
| No config | Port/host/limits hardcoded | `ServerConfig.fromEnv()` (`Config.kt`) — see [configuration.md](configuration.md) |
| No floor control | Every sender's frames relayed to everyone, always | `PttChannel` tracks one holder per channel; non-holder audio dropped with rate-limited `not_floor_holder` |
| No graceful shutdown | N/A | `Runtime.addShutdownHook` calls `server.stop(gracePeriodMillis, timeoutMillis)` (`Main.kt:25-30`) |

## Transport security

Two connectors, both optional, both serving the same application module:

| | Connector | Env |
|---|---|---|
| Plaintext | `ws://` on `PTT_PORT` | `PTT_HTTP_ENABLED` (default on) |
| TLS | `wss://` on `PTT_TLS_PORT` | `PTT_TLS_ENABLED` (default off) |

`tls/ServerKeyStore` loads `PTT_TLS_KEYSTORE`, or generates a self-signed keypair into it when the
file is absent, and publishes the certificate's SHA-256 fingerprint to the log and to
`<keystore>.sha256`. The client trusts that fingerprint rather than a chain — there is no domain
to prove ownership of on a LAN — which makes the keystore's *stability* the thing that matters:
generating a fresh keypair per boot would lock out every paired client.

Ktor's certificate builder still defaults to SHA-1 and to its own sample principal
(`O=JetBrains, C=RU`); both are overridden.

## Access control

`PTT_AUTH_TOKEN` is one shared secret, presented in an `X-PTT-Token` header and compared with
`MessageDigest.isEqual`. The check runs first in the WebSocket handler — before the version and
channel checks — so an unauthorised caller cannot use the error code to learn which channels
exist. `/health` is exempt so a `HEALTHCHECK` or load balancer still works.

`PTT_MAX_SESSIONS_PER_CHANNEL` bounds a channel. The test happens inside `PttChannel`'s own lock
(`tryJoin`), because a capacity check outside it lets a burst of simultaneous handshakes all read
"not full yet" and pile in. A refused join reaps the channel it may have just created.

## Not implemented

- **No accounts** — one shared token for everybody. No per-client identity beyond the
  server-generated `clientId` (a random UUID) and the client-supplied display `name`, no
  revocation, no audit trail.
- **No handshake rate limiting** — the per-channel cap bounds how many sessions exist, not how
  fast someone can try tokens.
- **No certificate rotation story** — pinning is excellent for a handful of handsets on a LAN and
  awkward beyond that. A permanently public relay wants a real certificate or a tunnel.
