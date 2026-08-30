# Configuration

All runtime configuration is read from the environment at startup, via `ServerConfig.fromEnv()`
(`src/main/kotlin/com/github/devapro/pttdroid/server/Config.kt:18-25`). There is no config file
and no command-line argument parsing.

## Environment variables

| Env var | Meaning | Default | Allowed range | Behavior outside range |
|---|---|---|---|---|
| `PTT_HOST` | Bind address for both connectors | `0.0.0.0` | any string | n/a (not validated as a range) |
| `PTT_PORT` | Plaintext (`ws://`) listen port | `8000` | `1..65535` | falls back to default |
| `PTT_HTTP_ENABLED` | Serve the plaintext connector at all | `true` | boolean | falls back to default |
| `PTT_MAX_AUDIO_FRAME_BYTES` | Max size of one binary audio frame; also derives the WebSocket `maxFrameSize` | `8192` | `64..1048576` | falls back to default |
| `PTT_PING_SECONDS` | WebSocket ping period *and* timeout (same value used for both) | `15` | `1..3600` | falls back to default |
| `PTT_MAX_CHANNEL` | Highest valid channel id; valid channels are `1..PTT_MAX_CHANNEL` | `99` | `1..9999` | falls back to default |
| `PTT_OUTBOUND_QUEUE` | Per-session bounded outbound queue capacity (frames) | `64` | `1..8192` | falls back to default |
| `PTT_MAX_SESSIONS_PER_CHANNEL` | Sessions admitted to one channel before further joins get `channel_full` | `32` | `1..10000` | falls back to default |
| `PTT_AUTH_TOKEN` | Shared secret every client must send in the `X-PTT-Token` header. Blank disables the check | *(blank)* | any string | n/a |
| `PTT_TRUST_FORWARDED_HEADERS` | Read `X-Forwarded-*` so logs name the real peer behind a proxy | `false` | boolean | falls back to default |

### TLS

| Env var | Meaning | Default |
|---|---|---|
| `PTT_TLS_ENABLED` | Serve `wss://` on a second connector | `false` |
| `PTT_TLS_PORT` | TLS listen port | `8443` |
| `PTT_TLS_KEYSTORE` | PKCS#12 keystore path. Generated on first boot if absent | `certs/ptt.p12` |
| `PTT_TLS_KEYSTORE_PASSWORD` | Keystore password | `changeit` (warned about at startup) |
| `PTT_TLS_KEY_ALIAS` | Alias of the key entry inside the keystore | `ptt` |
| `PTT_TLS_KEY_PASSWORD` | Private-key password | same as the keystore password |
| `PTT_TLS_SAN` | Comma-separated names and addresses to issue the generated certificate for | `localhost,127.0.0.1` |
| `PTT_TLS_VALIDITY_DAYS` | Lifetime of the generated certificate | `3650` |

Booleans accept `1/true/yes/on` and `0/false/no/off`, case-insensitively. Anything else keeps the
default — a typo never silently switches something on.

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
| `maxSessionsPerChannel` | `ChannelRegistry.tryJoinChannel`, checked under the channel lock so a burst of simultaneous joins cannot all read "not full yet" |
| `accessToken` | Handshake gate in `routing/ChannelRoutes.kt`, compared with `MessageDigest.isEqual` |
| `tls` | `ServerKeyStore.loadOrCreate` and the `sslConnector` in `Main.kt` |

`maxFrameSize` for the WebSocket plugin itself is *derived*, not a separate env var:
`(config.maxAudioFrameBytes * 2L).coerceAtLeast(16_384L)` (`plugins/Plugins.kt:42`) — headroom
above the audio limit so a control-message Text frame is never rejected as oversized.

## Startup warnings

`ServerConfig.warnings()` is printed at startup for configurations that are legal but probably
not what you meant:

- both connectors disabled — nothing would listen
- no `PTT_AUTH_TOKEN` — anyone who can reach the port can join a channel and listen
- `PTT_TLS_KEYSTORE_PASSWORD` left at the built-in default
- TLS enabled while the plaintext connector is still bound to `0.0.0.0`, which leaves audio
  readable off the wire on the other port

They are warnings, not errors: you may genuinely have a reverse proxy or a firewall handling it.

## TLS and the self-signed certificate

With `PTT_TLS_ENABLED=true` the server loads `PTT_TLS_KEYSTORE`, or generates a self-signed
keypair into it if the file is not there, and logs the certificate's SHA-256 fingerprint:

```
Tls -   7E:CF:C4:0A:AD:8D:2C:DD:76:96:CD:8B:1E:E3:7A:0F:B4:E8:B9:59:24:62:00:5B:C9:EB:6C:EB:F8:EE:8E:07
```

The same value is written to `<keystore>.sha256`, and `deploy/fingerprint.sh` prints it. It is
what the client pins: **Settings → Security → Certificate fingerprint**. It is not a secret — it
is a public certificate's digest — but it must reach the client over a channel you trust, because
substituting it is the one way to defeat the pin.

The keystore is deliberately a file rather than something regenerated per boot. Clients trust one
exact certificate, so a new keypair on every restart would lock out everyone already paired. Keep
it on a volume (`docker-compose.yml` mounts `ptt-certs:/app/certs` for exactly this).

Deleting the keystore regenerates it — and invalidates every client's pin.

## Authentication

`PTT_AUTH_TOKEN` is a single shared secret, sent by the client in an `X-PTT-Token` header and
compared in constant time. There are no accounts and no per-user revocation: changing the token
means telling everyone the new one.

It is a header rather than a query parameter on purpose — a URL ends up in proxy access logs, in
ngrok's request inspector, and in this server's own error logging.

`/health` stays open so a load balancer or `HEALTHCHECK` can still probe it.

## Example

```bash
PTT_HOST=0.0.0.0 \
PTT_PORT=9000 \
PTT_MAX_AUDIO_FRAME_BYTES=4096 \
PTT_PING_SECONDS=20 \
PTT_MAX_CHANNEL=20 \
PTT_OUTBOUND_QUEUE=32 \
PTT_AUTH_TOKEN="$(openssl rand -base64 24)" \
./gradlew run
```

TLS-only, with a certificate issued for a real LAN address:

```bash
PTT_HTTP_ENABLED=false \
PTT_TLS_ENABLED=true \
PTT_TLS_KEYSTORE=certs/ptt.p12 \
PTT_TLS_KEYSTORE_PASSWORD="$(openssl rand -base64 18)" \
PTT_TLS_SAN=localhost,127.0.0.1,192.168.1.20 \
PTT_AUTH_TOKEN="$(openssl rand -base64 24)" \
./gradlew run
```

Or via Docker Compose — see [`docker-compose.yml`](../docker-compose.yml), which sets the same
variables under `services.ptt-server.environment`.

## Client-side counterpart

The client is no longer hardcoded to one address. Server host, port, display name, channel,
`wss://`, the pinned certificate fingerprint and the access token are all user-configurable in
Settings, persisted via DataStore
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
