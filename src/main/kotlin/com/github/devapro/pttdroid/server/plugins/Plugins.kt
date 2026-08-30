package com.github.devapro.pttdroid.server.plugins

import com.github.devapro.pttdroid.server.ServerConfig
import com.github.devapro.pttdroid.server.domain.ChannelRegistry
import com.github.devapro.pttdroid.server.protocol.ProtocolJson
import com.github.devapro.pttdroid.server.routing.channelRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Plugins")

/**
 * Wires the whole application. Kept as one entry point so tests can spin up an identical
 * server with a custom [config].
 */
fun Application.pttModule(
    config: ServerConfig,
    registry: ChannelRegistry = ChannelRegistry(),
) {
    install(ContentNegotiation) {
        json(ProtocolJson)
    }

    // Behind ngrok or any reverse proxy every connection appears to come from the proxy, so
    // logs name the tunnel instead of the peer. Opt-in, because a client can forge these
    // headers when nothing in front of us is overwriting them.
    if (config.trustForwardedHeaders) {
        install(XForwardedHeaders)
    }

    install(WebSockets) {
        // Ktor 3 exposes these as millisecond Longs; the java.time.Duration properties the
        // 2.x code used are gone.
        pingPeriodMillis = config.pingSeconds.seconds.inWholeMilliseconds
        timeoutMillis = config.pingSeconds.seconds.inWholeMilliseconds
        masking = false
        // The old server used Long.MAX_VALUE, which let a single frame allocate without
        // bound. Cap it a little above the audio limit to leave room for control text.
        maxFrameSize = (config.maxAudioFrameBytes * 2L).coerceAtLeast(16_384L)
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled failure on {}", call.request.local.uri, cause)
            call.respondText(
                text = """{"error":"internal_error"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError,
            )
        }
    }

    routing {
        channelRoutes(config, registry)
    }
}
