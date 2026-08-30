# ptt-server

The WebSocket audio relay behind [PTTdroid](https://github.com/devapro/ptt-client-android). Kotlin
+ Ktor, one JAR, no database, no state to back up.

It does two things: it keeps each channel's members apart, and it enforces **one talker per
channel**. Everything else — who is on air, whose button is disabled — is a consequence of that.

> **[`docs/protocol.md`](docs/protocol.md) is the canonical wire contract for this whole product.**
> This repo owns it; the client follows it. Change it here first.

## Run it

**Never done this before?** [`docs/running-your-own.md`](docs/running-your-own.md) is the
walkthrough — from a spare machine to two handsets talking, with the firewall, the address to
type into the app, and what each failure looks like. What follows is the short version.

```bash
cp .env.example .env                 # then fill in at least PTT_AUTH_TOKEN
docker compose up -d --build         # 0.0.0.0:8000
curl -s localhost:8000/health        # {"status":"ok","channels":0,"sessions":0,"protocolVersion":1}
```

Encrypted, with a self-signed certificate generated on first boot:

```bash
docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d --build
./deploy/fingerprint.sh              # the SHA-256 to paste into the client
```

Reachable from outside the LAN, through an ngrok tunnel:

```bash
docker compose -f docker-compose.yml -f docker-compose.ngrok.yml up -d --build
./deploy/ngrok-url.sh                # prints the client settings to type in
```

To a remote host over SSH:

```bash
PTT_DEPLOY_HOST=relay.example.com ./deploy/deploy.sh
```

Or without Docker (needs **JDK 21**):

```bash
./gradlew run                        # blocks in the foreground
./gradlew installDist                # build/install/PTTdroidServer/bin/PTTdroidServer
```

Then point the Android client at this machine — **Settings → Relay → Custom**, one address box
that also takes a whole pasted URL:

- **Emulator** → nothing to do: `10.0.2.2:8000` is what the app ships as **Relay → Default**,
  since inside an emulator `localhost` is the emulator itself
- **Phone on the same Wi-Fi** → this machine's LAN address, e.g. `192.168.1.20:8000`

Building the app for a group that already has a relay? Set it once in the client's
`relay.properties` and the APK arrives pointing at it.

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
| `PTT_MAX_SESSIONS_PER_CHANNEL` | `32` | Joins past this get `channel_full` |
| `PTT_AUTH_TOKEN` | *(blank)* | Shared secret, sent by clients as `X-PTT-Token`. Blank means no auth |
| `PTT_TLS_ENABLED` | `false` | Serve `wss://` as well |
| `PTT_TLS_PORT` | `8443` | TLS port |
| `PTT_TLS_KEYSTORE` | `certs/ptt.p12` | PKCS#12 keystore; generated self-signed if absent |
| `PTT_TLS_SAN` | `localhost,127.0.0.1` | Names the generated certificate is issued for |

Full table with ranges and call sites: [`docs/configuration.md`](docs/configuration.md).

## Encryption without a certificate authority

There is no domain to prove ownership of on a LAN, so `PTT_TLS_ENABLED=true` generates a
**self-signed** certificate into `PTT_TLS_KEYSTORE` on first boot and logs its SHA-256
fingerprint:

```
Tls -   7E:CF:C4:0A:AD:8D:2C:DD:76:96:CD:8B:1E:E3:7A:0F:B4:E8:B9:59:24:62:00:5B:C9:EB:6C:EB:F8:EE:8E:07
```

The client pins that exact value (**Settings → Security**) instead of checking a chain, which is
a stricter guarantee than a CA gives — it admits one key and nothing else. What it costs is
rotation: replacing the keypair means re-pairing every client, which is why the keystore is a
file on a volume and not regenerated per boot.

Verify it yourself with tools you already have:

```bash
echo | openssl s_client -connect <host>:8443 2>/dev/null \
  | openssl x509 -noout -fingerprint -sha256
```

## The protocol in one paragraph

A client connects to `ws://host:port/channel/{id}?name={displayName}&v=1` — `wss://` when TLS is
on, plus an `X-PTT-Token` header when the relay wants one — and gets a `welcome`
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
./gradlew build      # compiles and runs 45 tests
```

`ChannelRelayTest` drives real WebSocket clients against a real server: channel isolation, one
talker at a time, floor release on disconnect, oversized frames, invalid channels, and that
`welcome` really is the first frame. `AccessControlTest` covers the token gate and per-channel
capacity; `ServerKeyStoreTest` covers certificate generation, fingerprint stability across a
restart, and the SAN list; `ServerConfigTest` covers environment parsing and the startup
warnings.

## Not implemented

- **No accounts.** `PTT_AUTH_TOKEN` is one shared secret for everybody: no per-user credentials,
  no revoking one handset, no audit trail. Changing it means telling everyone the new one.
- **No handshake rate limiting.** `PTT_MAX_SESSIONS_PER_CHANNEL` bounds a channel, not the rate
  at which someone can try tokens. Put the relay behind something that does if it is permanently
  public.
- **No audio compression.** Raw 16 kHz mono PCM is roughly 32 kB/s per talker. Fine on a LAN,
  wasteful over the internet — Opus would be the natural next step and would need a protocol
  version bump.
- **No persistence.** Channels exist only while somebody is in them.

## Docs

| | |
|---|---|
| [`docs/running-your-own.md`](docs/running-your-own.md) | **Start here** — zero to two handsets talking, and what to do when it does not work |
| [`docs/protocol.md`](docs/protocol.md) | **Canonical wire protocol (v1)** |
| [`docs/architecture.md`](docs/architecture.md) | Registry, sessions, floor control, fixed defects |
| [`docs/configuration.md`](docs/configuration.md) | Every environment variable |
| [`docs/deployment.md`](docs/deployment.md) | Docker, TLS, ngrok, remote deploy, systemd, security posture |

## Licence

[GNU General Public License v3.0](LICENSE) — GPL-3.0-only, the same as the client.
