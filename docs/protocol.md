# Wire Protocol

**This document is the canonical wire contract for PTT.** The server repo owns it — the client
must conform to what's written here, not the other way around. Whenever the protocol changes,
update this file in the same change.

Protocol v1 is implemented on both sides. Server source:
[`src/main/kotlin/com/github/devapro/pttdroid/server/protocol/Messages.kt`](../src/main/kotlin/com/github/devapro/pttdroid/server/protocol/Messages.kt)
and
[`routing/ChannelRoutes.kt`](../src/main/kotlin/com/github/devapro/pttdroid/server/routing/ChannelRoutes.kt).
Client source:
[`network/protocol/Messages.kt`](../../ptt-client-android/app/src/main/java/com/github/devapro/pttdroid/network/protocol/Messages.kt)
and [`network/KtorPttConnection.kt`](../../ptt-client-android/app/src/main/java/com/github/devapro/pttdroid/network/KtorPttConnection.kt).

## Connect URL

```
ws://<host>:<port>/channel/{channelId}?name={displayName}&v=1
```

| Part | Rule | Enforced at |
|---|---|---|
| `{channelId}` | Integer in `1..maxChannel` (default `1..99`, see [configuration.md](configuration.md)) | `ChannelRoutes.kt:65-72` |
| `name` | Trimmed, truncated to 32 chars, defaults to `"Anon"` if missing/blank | `ChannelRoutes.kt:76-80` |
| `v` | Must equal `1` if present at all; omitting `v` is accepted | `ChannelRoutes.kt:54-61` |

An invalid channel or unsupported version never opens a session: the server sends one `error`
text frame, then closes with `CloseReason.VIOLATED_POLICY` (`ChannelRoutes.kt:136-143`).

### Transport

`ws://` and `wss://` are the same protocol; which one applies is a deployment choice, not a
protocol version. See [configuration.md](configuration.md#tls-and-the-self-signed-certificate).

A relay serving a self-signed certificate is trusted by **SHA-256 fingerprint of the certificate's
DER encoding**, not by chain — the client pins it and skips hostname verification, because a LAN
relay's address is not stable enough to name in a certificate. A relay behind a tunnel or a real
certificate is verified normally, with no pin.

### Headers

| Header | Rule |
|---|---|
| `X-PTT-Token` | Required when the relay is started with a token; compared in constant time. Rejected connections get `unauthorized` |

Checked **before** the channel id and the version, so an unauthorised caller cannot use the error
code to probe which channels exist. `/health` is exempt.

The token is a header rather than a query parameter deliberately: a URL reaches proxy access
logs, tunnel inspectors and server-side error logging, and a secret that lands in all three is
not a secret.

## Framing

WebSocket frame **type is the discriminator** — there is no envelope or header on binary frames:

| Frame type | Meaning |
|---|---|
| Binary | Audio payload: PCM16LE, mono, **16000 Hz**, frame size **1280 bytes** (40 ms), **max 8192 bytes**, length must be even |
| Text | One JSON control message with a `type` field (`kotlinx.serialization` class discriminator, `Messages.kt:91`) |

Audio frames are validated in `ChannelRoutes.kt:145-171`: a frame over `maxAudioFrameBytes` (server
config, default 8192) or of odd length is rejected with `frame_too_large` and never relayed.

## Control messages (JSON over Text frames)

**Client → server** (`Messages.kt:24-32`):

| Type | Payload | Meaning |
|---|---|---|
| `talk_request` | `{}` | Client wants the floor |
| `talk_release` | `{}` | Client releases the floor |

**Server → client** (`Messages.kt:34-65`):

| Type | Payload | Meaning |
|---|---|---|
| `welcome` | `{clientId, channel, peers, audio: {sampleRate, channels, encoding, frameBytes}}` | Sent once, and it is genuinely the **first frame** on the socket. `audio` is fixed: `{16000, 1, "pcm16le", 1280}` |
| `floor` | `{holderId?, holderName?, isSelf}` | Who holds the floor, rendered **per recipient** — `isSelf` differs for each session even though the underlying holder is the same (`PttChannel.kt:96-103`) |
| `peers` | `{count}` | Peer-count update on join/leave, broadcast to the channel **except the joiner** — their count is already in `welcome`, and sending it would land ahead of the welcome |
| `error` | `{code, message}` | A rejected request or handshake failure |

### Error codes (`Messages.kt:77-84`)

| Code | When |
|---|---|
| `unsupported_version` | `v` query param present and not `1` |
| `invalid_channel` | `{channelId}` missing, non-numeric, or outside `1..maxChannel` |
| `floor_busy` | `talk_request` while another session holds the floor |
| `not_floor_holder` | Binary audio frame sent by a session that doesn't hold the floor |
| `frame_too_large` | Binary frame over the configured max, or odd-length |
| `malformed_message` | Text frame that doesn't parse as a known `ClientMessage` |
| `unauthorized` | The relay requires a token and the `X-PTT-Token` header was missing or wrong |
| `channel_full` | The channel already holds `PTT_MAX_SESSIONS_PER_CHANNEL` sessions |

`not_floor_holder` and `frame_too_large` are rate-limited to at most one report per second per
session (`PttSession.shouldReportError()`, `PttSession.kt:50-58`) — otherwise a client streaming
audio without the floor would earn one error per 40 ms frame.

## Floor control

Enforced per channel in `PttChannel.kt`:

- One holder at a time. `talk_request` on a free floor grants it and broadcasts `floor` to
  everyone on the channel (`requestFloor`, `PttChannel.kt:46-66`).
- A repeat `talk_request` from the current holder is idempotent — it just re-sends `floor` to
  that one caller (`FloorRequestResult.ALREADY_HELD_BY_SELF`).
- `talk_request` while someone else holds the floor gets `error{floor_busy}` back; the floor is
  not touched.
- Only the holder's binary frames are relayed (`relayAudio`, `PttChannel.kt:80-86`); everyone
  else's audio is silently dropped (with the rate-limited `not_floor_holder` error above). The
  sender itself is always excluded from the relay — audio is never echoed back to its source.
- `talk_release`, or disconnecting while holding the floor, frees it and broadcasts an updated
  `floor{holderId: null}` (`releaseFloor`, `PttChannel.kt:69-74`; release-on-disconnect in
  `PttChannel.leave`, `PttChannel.kt:36-44`).

## Fan-out mechanism

Each session owns a bounded `Channel<Frame>(capacity = outboundQueueSize, DROP_OLDEST)` drained
by its own writer coroutine (`PttSession.kt:19-30`, writer coroutine in `ChannelRoutes.kt:86-98`).
Broadcasting (`PttChannel.broadcastLocked`/`relayAudio`) calls `PttSession.offer`, which is a
non-suspending `trySend` — so fan-out to N peers under the channel mutex never awaits a socket
write. Consequences:

- A slow peer's queue fills and starts dropping *its own* incoming audio (oldest-first); nobody
  else on the channel is affected.
- A write failure on one peer's socket surfaces in that peer's own writer coroutine, not in the
  sender's request-handling coroutine — one broken connection cannot take down another session.

## Session lifecycle

```
connect /channel/{id}?name&v
        │
        ▼ validate v, then channelId  (reject + close on failure)
registry.joinChannel(id, session)  →  channel.join()  →  broadcasts `peers`
        │
        ▼
send `welcome`  (always first)
        │
        ▼
for each incoming frame:
    Binary → validate size/parity → channel.relayAudio (only if floor holder)
    Text   → decode → talk_request / talk_release → channel.requestFloor / releaseFloor
        │
        ▼ (loop ends on close, error, or cancellation)
registry.leaveChannel(id, session.id)
    → channel.leave(): releases floor if held, broadcasts `floor` + `peers`
    → channel discarded from the registry if now empty
session.closeQueue(); writer coroutine cancelled
```

Source: `ChannelRoutes.kt:53-133`.

## Health check

```
GET /health
```

Returns (`HealthResponse`, `Messages.kt:69-74`, handler `ChannelRoutes.kt:40-51`):

```json
{"status": "ok", "channels": 2, "sessions": 5, "protocolVersion": 1}
```

`channels`/`sessions` come from `ChannelRegistry.snapshot()` (`ChannelRegistry.kt:38-39`) — only
non-empty channels are counted, since empty channels are reaped immediately on last-leave
(`ChannelRegistry.leaveChannel`, `ChannelRegistry.kt:28-36`).

## Ktor 2 → 3 note

The `WebSockets` plugin install (`plugins/Plugins.kt:34-43`) sets `pingPeriodMillis` /
`timeoutMillis` as `Long` milliseconds. Ktor 2's `pingPeriod`/`timeout` took `java.time.Duration`
— that API is gone in Ktor 3. `maxFrameSize` is now `(maxAudioFrameBytes * 2).coerceAtLeast(16384)`
instead of `Long.MAX_VALUE`, leaving headroom above the audio limit for control-text frames.

## What this closes out

Protocol v1, as described above, fixes every defect the pre-refactor version of this document
recorded as "planned": channel isolation is enforced (not just parsed), floor control is
server-side, and both are covered by
[`ChannelRelayTest.kt`](../src/test/kotlin/com/github/devapro/pttdroid/server/ChannelRelayTest.kt)
(45 tests, all passing).
