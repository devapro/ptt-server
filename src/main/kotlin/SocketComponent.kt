package com.github.devapro.pttdroid.server

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Duration
import java.util.*
import kotlin.collections.LinkedHashSet

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        val connections = Collections.synchronizedSet<DefaultWebSocketSession>(LinkedHashSet())
        webSocket("/channel/*") {
            connections += this
            try {
                for (frame in incoming) {
                    connections.filter { it != this }.forEach {
                        it.send(frame)
                    }
                }
            } catch (e: Exception) {
                println(e.localizedMessage)
            } finally {
                connections -= this
            }
        }
    }
}