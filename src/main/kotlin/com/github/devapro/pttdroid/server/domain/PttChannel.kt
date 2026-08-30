package com.github.devapro.pttdroid.server.domain

import com.github.devapro.pttdroid.server.protocol.ErrorCodes
import com.github.devapro.pttdroid.server.protocol.Floor
import com.github.devapro.pttdroid.server.protocol.Peers
import com.github.devapro.pttdroid.server.protocol.ProtocolError
import io.ktor.websocket.Frame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of a talk-floor request, for logging by the caller. */
enum class FloorRequestResult { GRANTED, ALREADY_HELD_BY_SELF, BUSY }

/**
 * A single PTT channel: the set of sessions tuned to it, plus the talk floor.
 *
 * All mutation goes through [mutex]. Fan-out uses [PttSession.offer], which never suspends,
 * so broadcasting inside the lock cannot deadlock on a slow peer.
 */
class PttChannel(val id: Int) {

    private val mutex = Mutex()
    private val sessions = LinkedHashMap<String, PttSession>()
    private var floorHolderId: String? = null

    suspend fun join(session: PttSession): Int = mutex.withLock {
        sessions[session.id] = session
        broadcastLocked(Peers(sessions.size), exceptId = session.id)
        sessions.size
    }

    /**
     * Admits [session] only while the channel is below [capacity], and reports the new peer
     * count. Returns null when the channel is full.
     *
     * The check has to happen under the same lock as the insert: a capacity test outside it
     * lets a burst of simultaneous handshakes all read the same "not full yet" and pile in.
     */
    suspend fun tryJoin(session: PttSession, capacity: Int): Int? = mutex.withLock {
        if (sessions.size >= capacity) return@withLock null
        sessions[session.id] = session
        broadcastLocked(Peers(sessions.size), exceptId = session.id)
        sessions.size
    }

    /**
     * Removes [sessionId]. If it held the floor, the floor is released and the remaining
     * peers are told. Returns the new peer count.
     */
    suspend fun leave(sessionId: String): Int = mutex.withLock {
        sessions.remove(sessionId)
        if (floorHolderId == sessionId) {
            floorHolderId = null
            broadcastFloorLocked()
        }
        broadcastLocked(Peers(sessions.size))
        sessions.size
    }

    suspend fun requestFloor(sessionId: String): FloorRequestResult = mutex.withLock {
        val holder = floorHolderId
        when (holder) {
            null -> {
                floorHolderId = sessionId
                broadcastFloorLocked()
                FloorRequestResult.GRANTED
            }
            sessionId -> {
                // Idempotent: re-state the floor to just this caller.
                sessions[sessionId]?.send(floorMessageFor(sessionId))
                FloorRequestResult.ALREADY_HELD_BY_SELF
            }
            else -> {
                sessions[sessionId]?.send(
                    ProtocolError(ErrorCodes.FLOOR_BUSY, "Channel $id is busy"),
                )
                FloorRequestResult.BUSY
            }
        }
    }

    /** Returns true when [sessionId] actually held the floor and it was released. */
    suspend fun releaseFloor(sessionId: String): Boolean = mutex.withLock {
        if (floorHolderId != sessionId) return@withLock false
        floorHolderId = null
        broadcastFloorLocked()
        true
    }

    /**
     * Relays an audio frame to every other session on this channel. Returns false when the
     * sender does not hold the floor, in which case nothing is relayed.
     */
    suspend fun relayAudio(sessionId: String, frame: Frame.Binary): Boolean = mutex.withLock {
        if (floorHolderId != sessionId) return@withLock false
        for ((peerId, peer) in sessions) {
            if (peerId != sessionId) peer.offer(frame)
        }
        true
    }

    suspend fun peerCount(): Int = mutex.withLock { sessions.size }

    suspend fun isEmpty(): Boolean = mutex.withLock { sessions.isEmpty() }

    suspend fun floorHolder(): String? = mutex.withLock { floorHolderId }

    // --- helpers; all require [mutex] to be held -----------------------------------------

    private fun floorMessageFor(recipientId: String): Floor {
        val holderId = floorHolderId
        return Floor(
            holderId = holderId,
            holderName = holderId?.let { sessions[it]?.name },
            isSelf = holderId != null && holderId == recipientId,
        )
    }

    /** `isSelf` differs per recipient, so each session gets its own rendering. */
    private fun broadcastFloorLocked() {
        for ((recipientId, session) in sessions) {
            session.send(floorMessageFor(recipientId))
        }
    }

    /**
     * [exceptId] exists for the join case: the protocol promises `welcome` is the first thing
     * a client sees, and the joiner's own count is already in it. Sending them a `peers` from
     * inside their join would land ahead of the welcome and make that promise false.
     */
    private fun broadcastLocked(message: Peers, exceptId: String? = null) {
        for ((id, session) in sessions) {
            if (id != exceptId) session.send(message)
        }
    }
}
