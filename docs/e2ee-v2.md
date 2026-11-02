# PTT Protocol v2 — End-to-End Encrypted Audio

Status: DESIGN ONLY — NOT IMPLEMENTED

## Transport
- WSS is mandatory.
- v=2 is mandatory.
- No automatic downgrade to v1.
- v1 and v2 sessions must never share a channel.

## Audio framing
Binary WebSocket frame:
- 4 bytes: ASCII "PTT2"
- 1 byte: envelope version 0x01
- Remaining bytes: opaque MLS encrypted application message

The relay MUST NOT decode audio or possess group decryption keys.
The "PTT2" marker alone does not provide encryption or authenticity.

## Encrypted audio content
The authenticated MLS application message contains:
- Message type: audio
- Monotonic audio sequence number
- 40 ms PCM16LE audio frame (1280 bytes)

The receiving client must authenticate the sender,
reject invalid ciphertext and replayed messages,
and never play audio that fails authentication.

## Group security
- Authenticated MLS group membership
- Device identity verification
- Secure invitation / joining
- Key rotation when membership changes
- Revocation of lost devices
- No hardcoded group key in APK

## Relay responsibilities
- Authenticate authorized connections
- Enforce channel isolation and floor control
- Forward encrypted payloads only
- Enforce packet size and connection limits
- Never store audio or group secrets

## Required tests
- v1/v2 downgrade rejection
- Invalid ciphertext rejection
- Replay and reordered packet handling
- Unauthorized member rejection
- Group key rotation
- Multi-device audio fan-out
- No persistent audio storage

The protocol must not be enabled for production until
both endpoints implement and pass these requirements.
