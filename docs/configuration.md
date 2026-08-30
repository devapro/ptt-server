# Configuration

All runtime configuration is read from the environment at startup, via `ServerConfig.fromEnv()`
(`src/main/kotlin/com/github/devapro/pttdroid/server/Config.kt:18-25`). There is no config file
and no command-line argument parsing.

## Environment variables

| Env var | Meaning | Default | Allowed range | Behavior outside range |
|---|---|---|---|---|
| `PTT_HOST` | Bind address | `0.0.0.0` | any string | n/a (not validated as a range) |
| `PTT_PORT` | Listen port | `8000` | `1..65535` | falls back to default |
| `PTT_MAX_AUDIO_FRAME_BYTES` | Max size of one binary audio frame; also derives the WebSocket `maxFrameSize` | `8192` | `64..1048576` | falls back to default |
| `PTT_PING_SECONDS` | WebSocket ping period *and* timeout (same value used for both) | `15` | `1..3600` | falls back to default |
| `PTT_MAX_CHANNEL` | Highest valid channel id; valid channels are `1..PTT_MAX_CHANNEL` | `99` | `1..9999` | falls back to default |
| `PTT_OUTBOUND_QUEUE` | Per-session bounded outbound queue capacity (frames) | `64` | `1..8192` | falls back to default |

Parsing is defensive: `String?.toIntOr(default, allowed)` (`Config.kt:27-30`) returns the default
on a missing/blank/non-numeric value *or* a value outside the allowed range — an unparseable or
out-of-range env var never crashes startup, it silently falls back.

## Where each setting is used

| Setting | Consumer |
|---|---|
| `host`, `port` | `embeddedServer(Netty, port = config.port, host = config.host)` (`Main.kt:17-23`) |
| `maxAudioFrameBytes` | Audio frame size/parity check (`routing/ChannelRoutes.kt:151-152`); also feeds `maxFrameSize` below |
| `pingSeconds` | `WebSockets { pingPeriodMillis = ...; timeoutMillis = ... }` (`plugins/Plugins.kt:37-38`) — same value drives both ping period and timeout |
| `maxChannel` (via `channelRange = 1..maxChannel`) | Channel id validation on connect (`routing/ChannelRoutes.kt:66`) |
| `outboundQueueSize` | `PttSession`'s bounded outbound `Channel` capacity (`domain/PttSession.kt:22-27`) |

`maxFrameSize` for the WebSocket plugin itself is *derived*, not a separate env var:
`(config.maxAudioFrameBytes * 2L).coerceAtLeast(16_384L)` (`plugins/Plugins.kt:42`) — headroom
above the audio limit so a control-message Text frame is never rejected as oversized.

## Example

```bash
PTT_HOST=0.0.0.0 \
PTT_PORT=9000 \
PTT_MAX_AUDIO_FRAME_BYTES=4096 \
PTT_PING_SECONDS=20 \
PTT_MAX_CHANNEL=20 \
PTT_OUTBOUND_QUEUE=32 \
./gradlew run
```

Or via Docker Compose — see [`docker-compose.yml`](../docker-compose.yml), which sets the same
variables under `services.ptt-server.environment`.

## Client-side counterpart

The client is no longer hardcoded to one address. Server host, port, display name, and channel are
user-configurable in Settings, persisted via DataStore
(`data/settings/{AppSettings,SettingsRepository}.kt` in the client repo — see
[`ptt-client-android/docs/architecture.md`](../../ptt-client-android/docs/architecture.md)). The
default host is `10.0.2.2` (the emulator's alias for the host loopback interface), so an
unconfigured debug build on an emulator reaches a server started with the defaults above out of
the box. Point a physical device at the host's LAN IP instead; see
[deployment.md](deployment.md) for firewall/LAN notes.

As of the refactor, the client can also *host* its own relay in-process instead of pointing at a
separate `ptt-server` — see the client's `internalserver/InternalPttServer.kt` and
[`ptt-client-android/docs/architecture.md`](../../ptt-client-android/docs/architecture.md). That
internal server is a separate, compact reimplementation of the same protocol v1 contract
documented here; it is not this Ktor/Netty process and has no environment-variable configuration
of its own — it is toggled from the client's Settings screen.
