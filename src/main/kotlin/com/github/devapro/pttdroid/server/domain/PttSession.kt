package com.github.devapro.pttdroid.server.domain

import com.github.devapro.pttdroid.server.protocol.ServerMessage
import com.github.devapro.pttdroid.server.protocol.encode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import io.ktor.websocket.Frame
import java.util.concurrent.atomic.AtomicLong

/**
 * One connected client.
 *
 * Every session owns a bounded outbound queue drained by its own writer coroutine. Senders
 * never await a peer's socket: they [offer] into the peer's queue and move on. That is what
 * keeps one slow client from stalling the whole channel, and keeps a write failure on one
 * socket from tearing down the sender's session.
 */
class PttSession(
    val id: String,
    val name: String,
    queueCapacity: Int,
) {
    private val outbound = Channel<Frame>(
        capacity = queueCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Frames for this client's writer coroutine to drain. */
    val outgoing: ReceiveChannel<Frame> get() = outbound

    private val droppedFrames = AtomicLong(0)
    private val lastErrorAtMs = AtomicLong(0)

    /** Audio frames dropped because this client could not keep up. */
    val dropped: Long get() = droppedFrames.get()

    /**
     * Non-suspending enqueue. Safe to call while holding a lock, which is why broadcast can
     * fan out to every peer without releasing the channel mutex.
     */
    fun offer(frame: Frame): Boolean {
        val ok = outbound.trySend(frame).isSuccess
        if (!ok) droppedFrames.incrementAndGet()
        return ok
    }

    fun send(message: ServerMessage): Boolean = offer(Frame.Text(message.encode()))

    /**
     * Rate limiter for protocol errors on hot paths: a client streaming audio without the
     * floor would otherwise earn one error per 40 ms frame.
     */
    fun shouldReportError(nowMs: Long = System.currentTimeMillis()): Boolean {
        val previous = lastErrorAtMs.get()
        if (nowMs - previous < ERROR_INTERVAL_MS) return false
        return lastErrorAtMs.compareAndSet(previous, nowMs)
    }

    fun closeQueue() {
        outbound.close()
    }

    private companion object {
        const val ERROR_INTERVAL_MS = 1_000L
    }
}
