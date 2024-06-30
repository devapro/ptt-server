package com.github.devapro.pttdroid.server

import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    embeddedServer(Netty, port = 8000) {
        configureSockets()
        // ...
    }.start(wait = true)
}