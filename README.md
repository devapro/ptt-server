# ptt-server

The WebSocket audio relay behind [PTTdroid](https://github.com/devapro/ptt-client-android). Kotlin
+ Ktor, one JAR, no database, no state to back up.

It does two things: it keeps each channel's members apart, and it enforces **one talker per
channel**. Everything else — who is on air, whose button is disabled — is a consequence of that.

> **[`docs/protocol.md`](docs/protocol.md) is the canonical wire contract for this whole product.**
> This repo owns it; the client follows it. Change it here first.

## Run it

```bash
docker compose up --build            # 0.0.0.0:8000
curl -s localhost:8000/health        # {"status":"ok","channels":0,"sessions":0,"protocolVersion":1}
```

Or without Docker (needs **JDK 21**):

```bash
./gradlew run                        # blocks in the foreground
./gradlew installDist                # build/install/PTTdroidServer/bin/PTTdroidServer
```

Then point the Android client's **Settings → Relay** at this machine:

- **Emulator** → `10.0.2.2` — inside an emulator `localhost` is the emulator itself
- **Phone on the same Wi-Fi** → this machine's LAN address, e.g. `192.168.1.20`

## Configuration

Environment variables only — no config file, no arguments. An unparseable or out-of-range value
falls back to the default rather than failing startup.

| Variable | Default | What it does |
|---|---|---|
| `PTT_HOST` | `0.0.0.0` | Bind address |
| `PTT_PORT` | `8000` | Listen port |
| `PTT_MAX_AUDIO_FRAME_BYTES` | `8192` | Largest accepted audio frame; also sets the WebSocket `maxFrameSize` |
| `PTT_PING_SECONDS` | `15` | WebSocket ping period and timeout |
| `PTT_MAX_CHANNEL` | `99` | Valid channels are `1..PTT_MAX_CHANNEL` |
| `PTT_OUTBOUND_QUEUE` | `64` | Per-session outbound queue depth, in frames |

Full table with ranges and call sites: [`docs/configuration.md`](docs/configuration.md).

## The protocol in one paragraph

A client connects to `ws://host:port/channel/{id}?name={displayName}&v=1` and gets a `welcome`
carrying its client id and the shared audio parameters. Control messages are JSON text frames;
audio is raw binary frames of 16 kHz mono PCM. To speak, a client sends `talk_request` and waits —
it must **not** open its microphone until the server answers with a `floor` message naming it as
the holder. Everyone else on the channel receives that same `floor`, which is what disables their
button and names who is speaking. `talk_release` gives it back. Audio sent by anyone who does not
hold the floor is rejected with `not_floor_holder`.

Message-by-message spec, including the exact JSON: [`docs/protocol.md`](docs/protocol.md).

## How it is put together

```
ChannelRegistry ──▶ PttChannel (one per channel id)
                      ├── members: Set<PttSession>
                      └── floorHolderId: String?
                                │
PttSession ── outbound Channel<Frame>(64, DROP_OLDEST) ──▶ writer coroutine ──▶ socket
```

Each session owns a bounded outbound queue drained by its own writer coroutine, so fan-out to
peers is a non-suspending `trySend` each. That is deliberate: relaying by awaiting `send()` per
peer inside the sender's read loop meant one slow peer stalled the whole channel, and a write
failure to peer B killed peer A's session.

Internals and the full before/after table: [`docs/architecture.md`](docs/architecture.md).

## Build gate

```bash
./gradlew build      # compiles and runs the 14 relay tests
```

`ChannelRelayTest` drives real WebSocket clients against a real server: channel isolation, one
talker at a time, floor release on disconnect, oversized frames, invalid channels.

## Not implemented

- **No authentication and no TLS.** The transport is plain `ws://`. This is a LAN service; do not
  expose it to the open internet without putting a TLS-terminating reverse proxy and some form of
  auth in front of it.
- **No audio compression.** Raw 16 kHz mono PCM is roughly 32 kB/s per talker. Fine on a LAN,
  wasteful over the internet — Opus would be the natural next step and would need a protocol
  version bump.
- **No persistence.** Channels exist only while somebody is in them.

## Docs

| | |
|---|---|
| [`docs/protocol.md`](docs/protocol.md) | **Canonical wire protocol (v1)** |
| [`docs/architecture.md`](docs/architecture.md) | Registry, sessions, floor control, fixed defects |
| [`docs/configuration.md`](docs/configuration.md) | Every environment variable |
| [`docs/deployment.md`](docs/deployment.md) | Docker, systemd, firewall notes |
