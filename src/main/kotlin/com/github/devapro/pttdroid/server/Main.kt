package com.github.devapro.pttdroid.server

import com.github.devapro.pttdroid.server.plugins.pttModule
import com.github.devapro.pttdroid.server.tls.ServerKeyStore
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("Main")

fun main() {
    val config = ServerConfig.fromEnv()

    if (!config.plaintextEnabled && !config.tls.enabled) {
        log.error("PTT_HTTP_ENABLED and PTT_TLS_ENABLED are both off — nothing to listen on")
        exitProcess(1)
    }

    val keyStore = if (config.tls.enabled) {
        try {
            ServerKeyStore.loadOrCreate(config.tls)
        } catch (e: Exception) {
            log.error("TLS is enabled but the keystore could not be opened", e)
            exitProcess(1)
        }
    } else {
        null
    }

    log.info(
        "Starting PTT relay — channels {}, max audio frame {} bytes, auth {}",
        config.channelRange,
        config.maxAudioFrameBytes,
        if (config.requiresAuth) "required" else "OFF",
    )
    if (config.plaintextEnabled) log.info("  ws://{}:{}/channel/<n>", config.host, config.port)
    if (keyStore != null) log.info("  wss://{}:{}/channel/<n>", config.host, config.tls.port)
    config.warnings().forEach { log.warn(it) }

    val server = embeddedServer(
        factory = Netty,
        rootConfig = serverConfig { module { pttModule(config) } },
        configure = {
            if (config.plaintextEnabled) {
                connector {
                    host = config.host
                    port = config.port
                }
            }
            if (keyStore != null) {
                sslConnector(
                    keyStore = keyStore.keyStore,
                    keyAlias = config.tls.keyAlias,
                    keyStorePassword = { config.tls.keyStorePassword.toCharArray() },
                    privateKeyPassword = { config.tls.keyPassword.toCharArray() },
                ) {
                    host = config.host
                    port = config.tls.port
                    keyStorePath = keyStore.file
                }
            }
        },
    )

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Shutdown requested — draining connections")
            server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        },
    )

    server.start(wait = true)
}
