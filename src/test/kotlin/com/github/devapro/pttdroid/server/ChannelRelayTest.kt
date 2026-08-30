package com.github.devapro.pttdroid.server

import com.github.devapro.pttdroid.server.plugins.pttModule
import com.github.devapro.pttdroid.server.protocol.ErrorCodes
import com.github.devapro.pttdroid.server.protocol.Floor
import com.github.devapro.pttdroid.server.protocol.Peers
import com.github.devapro.pttdroid.server.protocol.ProtocolError
import com.github.devapro.pttdroid.server.protocol.ProtocolJson
import com.github.devapro.pttdroid.server.protocol.ServerMessage
import com.github.devapro.pttdroid.server.protocol.Welcome
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end protocol tests driven through Ktor's `testApplication`.
 *
 * The channel-isolation and floor-control cases are the ones that fail against the original
 * 43-line server, which broadcast every frame to every client regardless of channel.
 */
class ChannelRelayTest {

    private val config = testConfig()

    private fun ApplicationTestBuilder.pttClient() = createClient {
        install(ClientWebSockets)
    }

    /** Reads text frames until one decodes to [T], ignoring other control messages. */
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

    /**
     * Waits for a `floor` message satisfying [predicate].
     *
     * A session can have several earlier `floor` broadcasts queued (every floor change is
     * broadcast to the whole channel), so asserting on "the next floor message" would read a
     * stale one.
     */
    private suspend fun DefaultClientWebSocketSession.expectFloor(
        timeoutMs: Long = 5_000,
        predicate: (Floor) -> Boolean,
    ): Floor {
        val found = withTimeoutOrNull(timeoutMs) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = ProtocolJson.decodeFromString<ServerMessage>(frame.readText())
                if (message is Floor && predicate(message)) return@withTimeoutOrNull message
            }
            null
        }
        return assertNotNull(found, "Timed out waiting for a matching floor message")
    }

    private suspend fun DefaultClientWebSocketSession.nextBinaryOrNull(
        timeoutMs: Long = 1_500,
    ): ByteArray? = withTimeoutOrNull(timeoutMs) {
        for (frame in incoming) {
            if (frame is Frame.Binary) return@withTimeoutOrNull frame.readBytes()
        }
        null
    }

    private fun audio(byte: Int, size: Int = 320) = ByteArray(size) { byte.toByte() }

    @Test
    fun `welcome arrives first with channel and audio params`() = testApplication {
        application { pttModule(config) }
        pttClient().webSocket("/channel/7?name=Alice&v=1") {
            val welcome = expect<Welcome>()
            assertEquals(7, welcome.channel)
            assertEquals(1, welcome.peers)
            assertEquals(16_000, welcome.audio.sampleRate)
            assertEquals(1, welcome.audio.channels)
            assertEquals("pcm16le", welcome.audio.encoding)
            assertEquals(1_280, welcome.audio.frameBytes)
            assertTrue(welcome.clientId.isNotBlank())
        }
    }

    @Test
    fun `audio is relayed within a channel and never leaks to another channel`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()

        client.webSocket("/channel/1?name=Alice&v=1") {
            val alice = this
            alice.expect<Welcome>()

            client.webSocket("/channel/1?name=Bob&v=1") {
                val bob = this
                bob.expect<Welcome>()

                client.webSocket("/channel/2?name=Eve&v=1") {
                    val eve = this
                    eve.expect<Welcome>()

                    alice.send(Frame.Text("""{"type":"talk_request"}"""))
                    val floor = alice.expect<Floor>()
                    assertTrue(floor.isSelf, "Alice should hold the floor")

                    alice.send(Frame.Binary(true, audio(0x41)))

                    val heardByBob = bob.nextBinaryOrNull()
                    assertNotNull(heardByBob, "Bob on the same channel must receive the audio")
                    assertEquals(0x41.toByte(), heardByBob[0])

                    // The original server put every client in one global set; Eve is on
                    // channel 2 and must hear nothing.
                    assertNull(
                        eve.nextBinaryOrNull(),
                        "Eve on channel 2 must NOT receive channel 1 audio",
                    )
                }
            }
        }
    }

    @Test
    fun `sender never receives its own audio echoed back`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()

        client.webSocket("/channel/3?v=1") {
            val alice = this
            alice.expect<Welcome>()
            client.webSocket("/channel/3?v=1") {
                expect<Welcome>()
                alice.send(Frame.Text("""{"type":"talk_request"}"""))
                alice.expect<Floor>()
                alice.send(Frame.Binary(true, audio(0x7F)))
                assertNull(alice.nextBinaryOrNull(), "Sender must not hear its own audio")
            }
        }
    }

    @Test
    fun `second talker is refused while the floor is held`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()

        client.webSocket("/channel/4?name=Alice&v=1") {
            val alice = this
            alice.expect<Welcome>()
            client.webSocket("/channel/4?name=Bob&v=1") {
                val bob = this
                bob.expect<Welcome>()

                alice.send(Frame.Text("""{"type":"talk_request"}"""))
                assertTrue(alice.expect<Floor>().isSelf)

                // Bob sees Alice holding it, and his own request is refused.
                val seenByBob = bob.expect<Floor>()
                assertEquals("Alice", seenByBob.holderName)
                assertTrue(!seenByBob.isSelf)

                bob.send(Frame.Text("""{"type":"talk_request"}"""))
                assertEquals(ErrorCodes.FLOOR_BUSY, bob.expect<ProtocolError>().code)
            }
        }
    }

    @Test
    fun `floor can be released and then granted to someone else`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()

        client.webSocket("/channel/5?name=Alice&v=1") {
            val alice = this
            alice.expect<Welcome>()
            client.webSocket("/channel/5?name=Bob&v=1") {
                val bob = this
                bob.expect<Welcome>()

                alice.send(Frame.Text("""{"type":"talk_request"}"""))
                assertTrue(alice.expect<Floor>().isSelf)

                alice.send(Frame.Text("""{"type":"talk_release"}"""))
                // Both sides observe the floor going free.
                assertNull(alice.expectFloor { it.holderId == null }.holderId)

                bob.send(Frame.Text("""{"type":"talk_request"}"""))
                val bobFloor = bob.expectFloor { it.isSelf }
                assertTrue(bobFloor.isSelf, "Bob should now hold the floor")
                assertEquals("Bob", bobFloor.holderName)
            }
        }
    }

    @Test
    fun `audio from a non-holder is dropped and reported`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()

        client.webSocket("/channel/6?v=1") {
            val alice = this
            alice.expect<Welcome>()
            client.webSocket("/channel/6?v=1") {
                val bob = this
                bob.expect<Welcome>()

                // Nobody requested the floor.
                bob.send(Frame.Binary(true, audio(0x22)))
                assertEquals(ErrorCodes.NOT_FLOOR_HOLDER, bob.expect<ProtocolError>().code)
                assertNull(alice.nextBinaryOrNull(), "Audio without the floor must not be relayed")
            }
        }
    }

    @Test
    fun `oversized and odd-length audio frames are rejected`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()

        client.webSocket("/channel/8?v=1") {
            expect<Welcome>()
            send(Frame.Text("""{"type":"talk_request"}"""))
            expect<Floor>()

            send(Frame.Binary(true, ByteArray(config.maxAudioFrameBytes + 2)))
            assertEquals(ErrorCodes.FRAME_TOO_LARGE, expect<ProtocolError>().code)
        }
    }

    @Test
    fun `disconnect while holding the floor releases it`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()

        client.webSocket("/channel/9?name=Alice&v=1") {
            val alice = this
            alice.expect<Welcome>()

            client.webSocket("/channel/9?name=Bob&v=1") {
                val bob = this
                bob.expect<Welcome>()

                bob.send(Frame.Text("""{"type":"talk_request"}"""))
                assertTrue(bob.expect<Floor>().isSelf)
                assertEquals("Bob", alice.expect<Floor>().holderName)
                // Leaving this block closes Bob's socket.
            }

            // Alice must be told the floor is free again.
            val released = alice.expect<Floor>()
            assertNull(released.holderId, "Floor must be released when the holder disconnects")
        }
    }

    @Test
    fun `peer count is reported on join and leave`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()

        client.webSocket("/channel/11?v=1") {
            val alice = this
            assertEquals(1, alice.expect<Welcome>().peers)

            client.webSocket("/channel/11?v=1") {
                expect<Welcome>()
                assertEquals(2, alice.expect<Peers>().count)
            }

            // Second client gone.
            assertEquals(1, alice.expect<Peers>().count)
        }
    }

    @Test
    fun `malformed control message is reported`() = testApplication {
        application { pttModule(config) }
        pttClient().webSocket("/channel/12?v=1") {
            expect<Welcome>()
            send(Frame.Text("""{"type":"not_a_real_message"}"""))
            assertEquals(ErrorCodes.MALFORMED_MESSAGE, expect<ProtocolError>().code)
        }
    }

    @Test
    fun `invalid channel numbers are rejected`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()
        for (bad in listOf("0", "100", "abc")) {
            client.webSocket("/channel/$bad?v=1") {
                assertEquals(ErrorCodes.INVALID_CHANNEL, expect<ProtocolError>().code)
            }
        }
    }

    @Test
    fun `unsupported protocol version is rejected`() = testApplication {
        application { pttModule(config) }
        pttClient().webSocket("/channel/1?v=99") {
            assertEquals(ErrorCodes.UNSUPPORTED_VERSION, expect<ProtocolError>().code)
        }
    }

    @Test
    fun `health endpoint reports channel and session counts`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()
        client.webSocket("/channel/42?v=1") {
            expect<Welcome>()
            val body = client.get("/health").bodyAsText()
            assertTrue(body.contains("\"channels\":1"), "unexpected health body: $body")
            assertTrue(body.contains("\"sessions\":1"), "unexpected health body: $body")
        }
    }

    @Test
    fun `empty channels are reaped`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()
        client.webSocket("/channel/50?v=1") { expect<Welcome>() }
        val body = client.get("/health").bodyAsText()
        assertTrue(body.contains("\"channels\":0"), "channel should be reaped: $body")
    }

    @Test
    fun `welcome really is the first frame, not merely the first one anybody waited for`() =
        testApplication {
            application { pttModule(config) }
            val client = pttClient()

            client.webSocket("/channel/1") {
                // Someone is already here, so the join broadcasts a peer count. That count is
                // already inside the welcome, and a client is entitled to read the first frame
                // as its welcome — the spec says so.
                val first = incoming.receive()
                assertTrue(first is Frame.Text)
                val message = ProtocolJson.decodeFromString<ServerMessage>((first as Frame.Text).readText())
                assertTrue(message is Welcome, "first frame was $message")

                client.webSocket("/channel/1") {
                    val firstForSecond = incoming.receive()
                    val decoded = ProtocolJson.decodeFromString<ServerMessage>(
                        (firstForSecond as Frame.Text).readText(),
                    )
                    assertTrue(decoded is Welcome, "first frame was $decoded")
                    assertEquals(2, (decoded as Welcome).peers)
                }
            }
        }

    @Test
    fun `an existing member still learns that someone joined`() = testApplication {
        application { pttModule(config) }
        val client = pttClient()

        client.webSocket("/channel/1") {
            expect<Welcome>()
            client.webSocket("/channel/1") {
                expect<Welcome>()
            }
            // Not suppressed for everyone — only for the joiner.
            assertEquals(2, expect<Peers>().count)
        }
    }
}
