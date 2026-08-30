package com.github.devapro.pttdroid.server.routing

import com.github.devapro.pttdroid.server.ServerConfig
import com.github.devapro.pttdroid.server.domain.ChannelRegistry
import com.github.devapro.pttdroid.server.domain.FloorRequestResult
import com.github.devapro.pttdroid.server.domain.PttSession
import com.github.devapro.pttdroid.server.protocol.ErrorCodes
import com.github.devapro.pttdroid.server.protocol.HealthResponse
import com.github.devapro.pttdroid.server.protocol.PROTOCOL_VERSION
import com.github.devapro.pttdroid.server.protocol.ProtocolError
import com.github.devapro.pttdroid.server.protocol.TalkRelease
import com.github.devapro.pttdroid.server.protocol.TalkRequest
import com.github.devapro.pttdroid.server.protocol.Welcome
import com.github.devapro.pttdroid.server.protocol.decodeClientMessage
import com.github.devapro.pttdroid.server.protocol.encode
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID

private val log = LoggerFactory.getLogger("ChannelRoutes")

private const val MAX_NAME_LENGTH = 32
private const val DEFAULT_NAME = "Anon"

fun Route.channelRoutes(config: ServerConfig, registry: ChannelRegistry) {

    get("/health") {
        val channels = registry.snapshot()
        call.respond(
            HttpStatusCode.OK,
            HealthResponse(
                status = "ok",
                channels = channels.size,
                sessions = channels.values.sum(),
                protocolVersion = PROTOCOL_VERSION,
            ),
        )
    }

    webSocket("/channel/{channelId}") {
        if (!isAuthorised(config)) {
            rejectHandshake(
                ErrorCodes.UNAUTHORIZED,
                "This relay requires an access token in the $TOKEN_HEADER header",
            )
            return@webSocket
        }

        val version = call.request.queryParameters["v"]?.toIntOrNull()
        if (version != null && version != PROTOCOL_VERSION) {
            rejectHandshake(
                ErrorCodes.UNSUPPORTED_VERSION,
                "Server speaks protocol v$PROTOCOL_VERSION, client asked for v$version",
            )
            return@webSocket
        }

        // The old server routed /channel/* and never read the wildcard, so every client on
        // every channel shared one global broadcast group. Parse and validate it properly.
        val channelId = call.parameters["channelId"]?.toIntOrNull()
        if (channelId == null || channelId !in config.channelRange) {
            rejectHandshake(
                ErrorCodes.INVALID_CHANNEL,
                "Channel must be an integer in ${config.channelRange}",
            )
            return@webSocket
        }

        val session = PttSession(
            id = UUID.randomUUID().toString(),
            name = call.request.queryParameters["name"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.take(MAX_NAME_LENGTH)
                ?: DEFAULT_NAME,
            queueCapacity = config.outboundQueueSize,
        )

        // One writer coroutine per session. Nothing else ever touches this socket's outgoing
        // side, so a stalled or failed write is contained to this session alone.
        val writer = launch {
            try {
                for (frame in session.outgoing) {
                    outgoing.send(frame)
                }
            } catch (_: ClosedReceiveChannelException) {
                // Session ended; normal.
            } catch (_: CancellationException) {
                throw CancellationException("writer cancelled")
            } catch (e: Exception) {
                log.debug("Writer for session {} stopped: {}", session.id, e.toString())
            }
        }

        val channel = registry.tryJoinChannel(channelId, session, config.maxSessionsPerChannel)
        if (channel == null) {
            session.closeQueue()
            writer.cancel()
            rejectHandshake(
                ErrorCodes.CHANNEL_FULL,
                "Channel $channelId already has ${config.maxSessionsPerChannel} listeners",
            )
            return@webSocket
        }
        val peers = channel.peerCount()
        log.info(
            "Session {} ({}) joined channel {} — {} peer(s)",
            session.id, session.name, channelId, peers,
        )

        // welcome must precede every other message.
        session.send(Welcome(clientId = session.id, channel = channelId, peers = peers))

        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> handleAudio(session, channel, frame, config)
                    is Frame.Text -> handleControl(session, channel, frame, channelId)
                    else -> Unit // ping/pong/close are handled by the engine
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // Client closed the connection; normal.
        } catch (_: CancellationException) {
            throw CancellationException("session cancelled")
        } catch (e: Exception) {
            log.warn("Session {} on channel {} failed: {}", session.id, channelId, e.toString(), e)
        } finally {
            registry.leaveChannel(channelId, session.id)
            session.closeQueue()
            writer.cancel()
            log.info(
                "Session {} ({}) left channel {} — dropped {} frame(s)",
                session.id, session.name, channelId, session.dropped,
            )
        }
    }
}

/**
 * Header the shared secret travels in.
 *
 * Deliberately not a query parameter: a URL ends up in proxy access logs, in ngrok's request
 * inspector, and in this server's own error logging, and a token that leaks into all three is
 * not a token.
 */
const val TOKEN_HEADER: String = "X-PTT-Token"

/**
 * True when no token is configured, or the client presented the right one.
 *
 * The comparison is constant-time — a plain `==` on strings returns as soon as two bytes
 * differ, which over enough attempts leaks the token one character at a time.
 */
private fun io.ktor.server.websocket.DefaultWebSocketServerSession.isAuthorised(
    config: ServerConfig,
): Boolean {
    if (!config.requiresAuth) return true
    val presented = call.request.headers[TOKEN_HEADER] ?: return false
    return MessageDigest.isEqual(
        presented.toByteArray(Charsets.UTF_8),
        config.accessToken.toByteArray(Charsets.UTF_8),
    )
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.rejectHandshake(
    code: String,
    message: String,
) {
    log.info("Rejecting connection: {} — {}", code, message)
    outgoing.send(Frame.Text(ProtocolError(code, message).encode()))
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, message))
}

private suspend fun handleAudio(
    session: PttSession,
    channel: com.github.devapro.pttdroid.server.domain.PttChannel,
    frame: Frame.Binary,
    config: ServerConfig,
) {
    val size = frame.data.size
    if (size > config.maxAudioFrameBytes || size % 2 != 0) {
        if (session.shouldReportError()) {
            session.send(
                ProtocolError(
                    ErrorCodes.FRAME_TOO_LARGE,
                    "Audio frame must be even-length and at most ${config.maxAudioFrameBytes} bytes",
                ),
            )
        }
        return
    }
    if (!channel.relayAudio(session.id, frame)) {
        // Not the floor holder — drop, and tell them at most once a second.
        if (session.shouldReportError()) {
            session.send(
                ProtocolError(ErrorCodes.NOT_FLOOR_HOLDER, "You do not hold the talk floor"),
            )
        }
    }
}

private suspend fun handleControl(
    session: PttSession,
    channel: com.github.devapro.pttdroid.server.domain.PttChannel,
    frame: Frame.Text,
    channelId: Int,
) {
    val message = try {
        decodeClientMessage(frame.readText())
    } catch (e: SerializationException) {
        log.debug("Malformed control message from {}: {}", session.id, e.toString())
        if (session.shouldReportError()) {
            session.send(ProtocolError(ErrorCodes.MALFORMED_MESSAGE, "Unparseable control message"))
        }
        return
    }

    when (message) {
        TalkRequest -> {
            val result = channel.requestFloor(session.id)
            log.info(
                "Floor request from {} on channel {}: {}",
                session.id, channelId, result.name.lowercase(),
            )
        }
        TalkRelease -> {
            if (channel.releaseFloor(session.id)) {
                log.info("Floor released by {} on channel {}", session.id, channelId)
            }
        }
    }
}
