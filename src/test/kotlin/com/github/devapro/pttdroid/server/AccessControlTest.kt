package com.github.devapro.pttdroid.server

import com.github.devapro.pttdroid.server.plugins.pttModule
import com.github.devapro.pttdroid.server.protocol.ErrorCodes
import com.github.devapro.pttdroid.server.protocol.ProtocolError
import com.github.devapro.pttdroid.server.protocol.ProtocolJson
import com.github.devapro.pttdroid.server.protocol.ServerMessage
import com.github.devapro.pttdroid.server.protocol.Welcome
import com.github.devapro.pttdroid.server.routing.TOKEN_HEADER
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The handshake gate: who is allowed onto a channel at all.
 *
 * These matter more than they used to. The relay now ships a TLS connector and an ngrok
 * compose overlay, which means it can be on the open internet in one command — and an audio
 * relay anyone can join is a listening device.
 */
class AccessControlTest {

    private val token = "correct-horse-battery-staple"

    private fun ApplicationTestBuilder.pttClient() = createClient {
        install(ClientWebSockets)
    }

    private suspend inline fun <reified T : ServerMessage> DefaultClientWebSocketSession.expect(
        timeoutMs: Long = 5_000,
    ): T {
        val found = withTimeoutOrNull(timeoutMs) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = ProtocolJson.decodeFromString<ServerMessage>(frame.readText())
                if (message is T) return@withTimeoutOrNull message
            }
            null
        }
        return assertNotNull(found, "Timed out waiting for ${T::class.simpleName}")
    }

    @Test
    fun `without a token configured, anyone may join`() = testApplication {
        application { pttModule(testConfig()) }

        pttClient().webSocket("/channel/1") {
            assertEquals(1, expect<Welcome>().channel)
        }
    }

    @Test
    fun `a configured token rejects a client that presents none`() = testApplication {
        application { pttModule(testConfig(accessToken = token)) }

        pttClient().webSocket("/channel/1") {
            assertEquals(ErrorCodes.UNAUTHORIZED, expect<ProtocolError>().code)
        }
    }

    @Test
    fun `a configured token rejects the wrong token`() = testApplication {
        application { pttModule(testConfig(accessToken = token)) }

        pttClient().webSocket(
            "/channel/1",
            request = { header(TOKEN_HEADER, "correct-horse-battery-stapl") },
        ) {
            assertEquals(ErrorCodes.UNAUTHORIZED, expect<ProtocolError>().code)
        }
    }

    @Test
    fun `the right token gets in`() = testApplication {
        application { pttModule(testConfig(accessToken = token)) }

        pttClient().webSocket("/channel/1", request = { header(TOKEN_HEADER, token) }) {
            assertEquals(1, expect<Welcome>().channel)
        }
    }

    @Test
    fun `auth is checked before the channel id, so a bad token cannot probe the channel range`() =
        testApplication {
            application { pttModule(testConfig(accessToken = token, maxChannel = 9)) }

            // Channel 50 is out of range; an unauthorised caller must not learn that.
            pttClient().webSocket("/channel/50") {
                assertEquals(ErrorCodes.UNAUTHORIZED, expect<ProtocolError>().code)
            }
        }

    @Test
    fun `health stays open so a load balancer can still probe it`() = testApplication {
        application { pttModule(testConfig(accessToken = token)) }

        assertEquals(200, pttClient().get("/health").status.value)
    }

    @Test
    fun `a channel at capacity turns the next client away`() = testApplication {
        application { pttModule(testConfig(maxSessionsPerChannel = 2)) }
        val client = pttClient()

        client.webSocket("/channel/1") {
            expect<Welcome>()
            client.webSocket("/channel/1") {
                expect<Welcome>()
                client.webSocket("/channel/1") {
                    assertEquals(ErrorCodes.CHANNEL_FULL, expect<ProtocolError>().code)
                }
            }
        }
    }

    @Test
    fun `a rejected client does not consume a slot`() = testApplication {
        application { pttModule(testConfig(maxSessionsPerChannel = 1)) }
        val client = pttClient()

        client.webSocket("/channel/1") {
            expect<Welcome>()
            client.webSocket("/channel/1") {
                assertEquals(ErrorCodes.CHANNEL_FULL, expect<ProtocolError>().code)
            }
        }
        // The first session has left; the channel must be joinable again.
        client.webSocket("/channel/1") {
            assertEquals(1, expect<Welcome>().peers)
        }
    }

    @Test
    fun `capacity is per channel, not global`() = testApplication {
        application { pttModule(testConfig(maxSessionsPerChannel = 1)) }
        val client = pttClient()

        client.webSocket("/channel/1") {
            expect<Welcome>()
            client.webSocket("/channel/2") {
                assertEquals(2, expect<Welcome>().channel)
            }
        }
    }
}
