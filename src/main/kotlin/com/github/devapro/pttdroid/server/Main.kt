package com.github.devapro.pttdroid.server

import com.github.devapro.pttdroid.server.plugins.pttModule
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val config = ServerConfig.fromEnv()
    log.info(
        "Starting PTT relay on {}:{} — channels {}, max audio frame {} bytes",
        config.host, config.port, config.channelRange, config.maxAudioFrameBytes,
    )

    val server = embeddedServer(
        factory = Netty,
        port = config.port,
        host = config.host,
    ) {
        pttModule(config)
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Shutdown requested — draining connections")
            server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        },
    )

    server.start(wait = true)
}
