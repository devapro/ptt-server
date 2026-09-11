package com.github.devapro.pttdroid.server.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Protocol version carried in the `v` query parameter. */
const val PROTOCOL_VERSION: Int = 1

/**
 * Audio format for the binary frames on a channel. Fixed for protocol v1 — there is no
 * negotiation, both peers must use exactly these parameters.
 */
@Serializable
data class AudioParams(
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val encoding: String = "pcm16le",
    val frameBytes: Int = 1_280,
)

/** Control messages sent by a client on a text frame. */
@Serializable
sealed interface ClientMessage

@Serializable
@SerialName("talk_request")
data object TalkRequest : ClientMessage

@Serializable
@SerialName("talk_release")
data object TalkRelease : ClientMessage

/**
 * Liveness probe. Answered with [Pong], and it touches no channel state.
 *
 * Deliberately separate from a WebSocket ping frame: the engines answer those themselves and
 * never surface either direction to application code, so a client holding an idle socket cannot
 * tell a healthy link from one whose peer vanished. This one both applications can see.
 */
@Serializable
@SerialName("ping")
data object Ping : ClientMessage

/** Control messages sent by the server on a text frame. */
@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("welcome")
data class Welcome(
    val clientId: String,
    val channel: Int,
    val peers: Int,
    val audio: AudioParams = AudioParams(),
) : ServerMessage

/**
 * Who currently holds the talk floor on this channel. [isSelf] is computed per recipient,
 * so this message is rendered once per session rather than broadcast verbatim.
 */
@Serializable
@SerialName("floor")
data class Floor(
    val holderId: String? = null,
    val holderName: String? = null,
    val isSelf: Boolean = false,
) : ServerMessage

@Serializable
@SerialName("peers")
data class Peers(val count: Int) : ServerMessage

@Serializable
@SerialName("error")
data class ProtocolError(val code: String, val message: String) : ServerMessage

/** Answer to [Ping]. Carries nothing: its arrival is the whole message. */
@Serializable
@SerialName("pong")
data object Pong : ServerMessage

/** Body of `GET /health`. A typed class, because kotlinx-serialization cannot encode Map<String, Any>. */
@Serializable
data class HealthResponse(
    val status: String,
    val channels: Int,
    val sessions: Int,
    val protocolVersion: Int,
)

/** Stable [ProtocolError.code] values. */
object ErrorCodes {
    const val UNSUPPORTED_VERSION = "unsupported_version"
    const val INVALID_CHANNEL = "invalid_channel"
    const val FLOOR_BUSY = "floor_busy"
    const val NOT_FLOOR_HOLDER = "not_floor_holder"
    const val FRAME_TOO_LARGE = "frame_too_large"
    const val MALFORMED_MESSAGE = "malformed_message"
    const val UNAUTHORIZED = "unauthorized"
    const val CHANNEL_FULL = "channel_full"
}

/**
 * Shared codec. `encodeDefaults` is required so [AudioParams] defaults reach the wire, and
 * `ignoreUnknownKeys` lets a newer client add fields without breaking an older server.
 */
val ProtocolJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = true
}

fun ServerMessage.encode(): String = ProtocolJson.encodeToString<ServerMessage>(this)

fun decodeClientMessage(text: String): ClientMessage = ProtocolJson.decodeFromString(text)
